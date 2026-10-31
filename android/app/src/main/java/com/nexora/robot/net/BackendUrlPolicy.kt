package com.nexora.robot.net

import java.net.URI
import java.net.URISyntaxException

/**
 * HTTPS-only policy for the NEX backend address.
 *
 * `usesCleartextTraffic` is false and the network security config forbids cleartext, but a
 * policy object is still worth having: it rejects a bad URL *before* any socket is opened and
 * it explains why in words the UI can show. Pure Kotlin, so it is unit tested.
 */
object BackendUrlPolicy {

    const val MAX_URL_CHARS = 300

    sealed interface UrlCheck {
        data class Valid(val normalizedUrl: String) : UrlCheck
        data class Invalid(val reason: String, val userMessage: String) : UrlCheck
    }

    fun normalize(raw: String): String = raw.trim().trimEnd('/')

    fun check(raw: String): UrlCheck {
        val candidate = normalize(raw)
        if (candidate.isBlank()) {
            return UrlCheck.Invalid("empty", "No server address has been set yet.")
        }
        if (candidate.length > MAX_URL_CHARS) {
            return UrlCheck.Invalid("too-long", "That server address is too long.")
        }
        val uri = try {
            URI(candidate)
        } catch (error: URISyntaxException) {
            return UrlCheck.Invalid("malformed", "That server address is not a valid URL.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") {
            return UrlCheck.Invalid(
                "not-https",
                "NEX only talks to an HTTPS server. Please use an address that starts with https://",
            )
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            return UrlCheck.Invalid("no-host", "That server address has no host name.")
        }
        if (uri.userInfo != null) {
            return UrlCheck.Invalid("credentials-in-url", "Please do not put a user name or password in the address.")
        }
        return UrlCheck.Valid(normalizedUrl = candidate)
    }

    fun isAllowedHttps(raw: String): Boolean = check(raw) is UrlCheck.Valid

    /**
     * @throws IllegalArgumentException when the address is not a clean https URL.
     */
    fun requireHttps(raw: String): String = when (val result = check(raw)) {
        is UrlCheck.Valid -> result.normalizedUrl
        is UrlCheck.Invalid -> throw IllegalArgumentException("NEX backend URL rejected (${result.reason})")
    }
}
