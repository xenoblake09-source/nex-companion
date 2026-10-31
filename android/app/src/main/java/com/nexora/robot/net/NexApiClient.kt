package com.nexora.robot.net

import android.util.Base64
import com.nexora.robot.util.NexLog
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The only component that talks to the NEX backend.
 *
 * Rules:
 *  * **HTTPS only** — enforced at construction by [BackendUrlPolicy] and again by the network
 *    security config;
 *  * **no credentials** — the app has no API key, no OAuth token and no device id. The Gemini
 *    key exists only on the server;
 *  * **no silent queueing** — a failed call returns [ConverseResult.Unavailable] so the UI can
 *    tell the truth. Nothing is stored for later upload;
 *  * **no body logging** — only sizes, statuses and timings are logged, through [NexLog].
 */
class NexApiClient(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    private val appVersion: String = "1.0.0",
    private val platform: String = "android",
) {

    private val root: String = BackendUrlPolicy.requireHttps(baseUrl)

    private sealed interface CallOutcome {
        data class Body(val status: Int, val json: JSONObject) : CallOutcome
        data class Failure(val code: String, val message: String) : CallOutcome
    }

    suspend fun health(): HealthResult = withContext(Dispatchers.IO) {
        when (val outcome = get("$root/health")) {
            is CallOutcome.Failure -> HealthResult.Unreachable(outcome.code, outcome.message)
            is CallOutcome.Body -> {
                val json = outcome.json
                HealthResult.Ok(
                    provider = json.optString("provider", ""),
                    model = json.optString("model", ""),
                    ready = json.optBoolean("ready", false),
                    groundingEnabled = json.optBoolean("grounding_enabled", false),
                    version = json.optString("version", ""),
                )
            }
        }
    }

    /**
     * One conversation turn.
     *
     * @param needsCurrentInfo explicit signal that the answer may need live information. The
     *   server still decides whether search grounding genuinely helps, and only reports
     *   sources when grounding really happened.
     */
    suspend fun converse(
        text: String,
        language: String?,
        needsCurrentInfo: Boolean,
    ): ConverseResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("text", text)
            if (!language.isNullOrBlank()) put("language", language)
            put("needs_current_info", needsCurrentInfo)
            put(
                "context",
                JSONObject().apply {
                    put("platform", platform)
                    put("app_version", appVersion)
                },
            )
        }

        when (val outcome = post("$root/v1/converse", payload)) {
            is CallOutcome.Failure -> ConverseResult.Unavailable(outcome.code, outcome.message)
            is CallOutcome.Body -> {
                val json = outcome.json
                if (outcome.status !in 200..299) {
                    return@withContext rejected(json, outcome.status)
                }
                val reply = json.optString("reply", "").trim()
                if (reply.isEmpty()) {
                    return@withContext ConverseResult.Unavailable(
                        NexErrorCodes.BAD_RESPONSE,
                        "My server answered with an empty message.",
                    )
                }
                ConverseResult.Success(
                    reply = reply,
                    language = json.optString("language", "en"),
                    grounded = json.optBoolean("grounded", false),
                    sources = parseSources(json.optJSONArray("sources")),
                )
            }
        }
    }

    /**
     * One vision turn, only ever called after the user explicitly asked about something in
     * front of them. The frame is held in memory, sent once, and never written to storage.
     */
    suspend fun describeImage(
        jpegBytes: ByteArray,
        mimeType: String,
        prompt: String,
        language: String?,
    ): VisionResult = withContext(Dispatchers.IO) {
        val encoded = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("image_base64", encoded)
            put("mime_type", mimeType)
            put("prompt", prompt)
            if (!language.isNullOrBlank()) put("language", language)
        }
        NexLog.i("vision request prepared bytes=${jpegBytes.size}")

        when (val outcome = post("$root/v1/vision", payload)) {
            is CallOutcome.Failure -> VisionResult.Unavailable(outcome.code, outcome.message)
            is CallOutcome.Body -> {
                val json = outcome.json
                if (outcome.status !in 200..299) {
                    val rejected = rejected(json, outcome.status)
                    return@withContext VisionResult.Rejected(rejected.code, rejected.message, rejected.httpStatus)
                }
                VisionResult.Success(
                    reply = json.optString("reply", "").trim(),
                    language = json.optString("language", "en"),
                    grounded = json.optBoolean("grounded", false),
                    sources = parseSources(json.optJSONArray("sources")),
                    imageStored = json.optBoolean("image_stored", false),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun rejected(json: JSONObject, status: Int): ConverseResult.Rejected {
        val error = json.optJSONObject("error")
        val code = error?.optString("code")?.takeIf { it.isNotBlank() } ?: NexErrorCodes.REJECTED
        val message = error?.optString("message")?.takeIf { it.isNotBlank() }
            ?: "My server could not answer that (status $status)."
        return ConverseResult.Rejected(code = code, message = message, httpStatus = status)
    }

    private fun parseSources(array: JSONArray?): List<SourceLink> {
        if (array == null) return emptyList()
        val links = ArrayList<SourceLink>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url", "").trim()
            val title = item.optString("title", "").trim()
            if (url.isEmpty() || title.isEmpty()) continue
            links.add(SourceLink(title = title, url = url))
        }
        return links
    }

    private fun get(url: String): CallOutcome = execute(
        Request.Builder().url(url).header("accept", "application/json").get().build(),
        url,
    )

    private fun post(url: String, payload: JSONObject): CallOutcome = execute(
        Request.Builder()
            .url(url)
            .header("accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build(),
        url,
    )

    private fun execute(request: Request, url: String): CallOutcome {
        val startedAt = System.nanoTime()
        return try {
            client.newCall(request).execute().use { response ->
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                val body = response.body?.string().orEmpty()
                NexLog.i("http ${request.method} status=${response.code} ms=$elapsedMs bytes=${body.length}")
                val json = body.toJsonObjectOrNull()
                    ?: return@use CallOutcome.Failure(
                        NexErrorCodes.BAD_RESPONSE,
                        "My server answered with something I could not read.",
                    )
                CallOutcome.Body(status = response.code, json = json)
            }
        } catch (timeout: SocketTimeoutException) {
            NexLog.w("http timeout path=${pathOf(url)}")
            CallOutcome.Failure(
                NexErrorCodes.TIMEOUT,
                "My server took too long to answer. Please try again in a moment.",
            )
        } catch (secure: SSLException) {
            NexLog.e("tls failure path=${pathOf(url)}", secure)
            CallOutcome.Failure(
                NexErrorCodes.INSECURE,
                "I could not make a secure connection to my server.",
            )
        } catch (io: IOException) {
            NexLog.w("network failure path=${pathOf(url)} type=${io.javaClass.simpleName}")
            CallOutcome.Failure(
                NexErrorCodes.OFFLINE,
                "I am offline, so I cannot reach my server right now.",
            )
        }
    }

    private fun pathOf(url: String): String = url.removePrefix(root).ifBlank { "/" }

    private fun String.toJsonObjectOrNull(): JSONObject? = try {
        JSONObject(this)
    } catch (error: Exception) {
        null
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // No interceptors that log bodies: user speech must not end up in logs.
            .build()
    }
}
