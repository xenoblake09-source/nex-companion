package com.nexora.robot.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The app only ever talks to one HTTPS address, and it validates that address before opening a
 * socket. No credentials may travel inside the URL, and a rejected address is never echoed back
 * to the user in an error message.
 */
class BackendUrlPolicyTest {

    @Test
    fun theLengthBoundIsThreeHundredCharacters() {
        assertEquals(300, BackendUrlPolicy.MAX_URL_CHARS)
    }

    @Test
    fun normalizeTrimsWhitespaceAndTrailingSlashes() {
        assertEquals("https://example.com", BackendUrlPolicy.normalize("  https://example.com  "))
        assertEquals("https://example.com", BackendUrlPolicy.normalize("https://example.com/"))
        assertEquals("https://example.com", BackendUrlPolicy.normalize("https://example.com///"))
        assertEquals("https://example.com/api", BackendUrlPolicy.normalize("https://example.com/api/"))
        assertEquals("https://example.com/api/v1", BackendUrlPolicy.normalize("https://example.com/api/v1"))
    }

    @Test
    fun aCleanHttpsAddressIsAccepted() {
        val result = BackendUrlPolicy.check("https://nex-companion-api.onrender.com")
        assertTrue(result is BackendUrlPolicy.UrlCheck.Valid)
        assertEquals(
            "https://nex-companion-api.onrender.com",
            (result as BackendUrlPolicy.UrlCheck.Valid).normalizedUrl,
        )
        assertTrue(BackendUrlPolicy.isAllowedHttps("https://nex-companion-api.onrender.com"))
        assertTrue(BackendUrlPolicy.isAllowedHttps("https://example.com/"))
        assertTrue(BackendUrlPolicy.isAllowedHttps("https://10.0.0.5:8443"))
    }

    @Test
    fun cleartextHttpIsRefused() {
        val result = BackendUrlPolicy.check("http://example.com")
        assertTrue(result is BackendUrlPolicy.UrlCheck.Invalid)
        assertEquals("not-https", (result as BackendUrlPolicy.UrlCheck.Invalid).reason)
        assertTrue(result.userMessage.contains("HTTPS"))
        assertFalse(BackendUrlPolicy.isAllowedHttps("http://example.com"))
        assertFalse(BackendUrlPolicy.isAllowedHttps("HTTP://EXAMPLE.COM"))
        assertFalse(BackendUrlPolicy.isAllowedHttps("ftp://example.com"))
        assertFalse(BackendUrlPolicy.isAllowedHttps("file:///etc/passwd"))
        assertFalse(BackendUrlPolicy.isAllowedHttps("ws://example.com"))
        assertFalse(BackendUrlPolicy.isAllowedHttps("example.com"))
    }

    @Test
    fun aBlankAddressIsRefusedWithAUsableMessage() {
        listOf("", "   ", "\t\n").forEach { raw ->
            val result = BackendUrlPolicy.check(raw)
            assertTrue(result is BackendUrlPolicy.UrlCheck.Invalid)
            assertEquals("empty", (result as BackendUrlPolicy.UrlCheck.Invalid).reason)
            assertTrue(result.userMessage.isNotBlank())
        }
    }

    @Test
    fun anOverlongAddressIsRefusedBeforeAnythingIsParsed() {
        val long = "https://example.com/" + "a".repeat(BackendUrlPolicy.MAX_URL_CHARS)
        assertTrue(long.length > BackendUrlPolicy.MAX_URL_CHARS)
        val result = BackendUrlPolicy.check(long)
        assertTrue(result is BackendUrlPolicy.UrlCheck.Invalid)
        assertEquals("too-long", (result as BackendUrlPolicy.UrlCheck.Invalid).reason)
    }

    @Test
    fun anAddressWithoutAHostIsRefused() {
        val result = BackendUrlPolicy.check("https://")
        assertTrue(result is BackendUrlPolicy.UrlCheck.Invalid)
        assertEquals("no-host", (result as BackendUrlPolicy.UrlCheck.Invalid).reason)
    }

    @Test
    fun credentialsInsideTheUrlAreRefusedAndNeverEchoed() {
        val raw = "https://user:secret-password@example.com"
        val result = BackendUrlPolicy.check(raw)
        assertTrue(result is BackendUrlPolicy.UrlCheck.Invalid)
        val invalid = result as BackendUrlPolicy.UrlCheck.Invalid
        assertEquals("credentials-in-url", invalid.reason)
        assertFalse("the address must not be echoed", invalid.userMessage.contains(raw))
        assertFalse(invalid.userMessage.contains("secret-password"))
        assertFalse(invalid.userMessage.contains("user:"))
    }

    @Test
    fun aMalformedAddressIsRefusedWithoutThrowing() {
        val result = BackendUrlPolicy.check("https://exa mple.com/ not a url")
        assertTrue(result is BackendUrlPolicy.UrlCheck.Invalid)
        assertEquals("malformed", (result as BackendUrlPolicy.UrlCheck.Invalid).reason)
    }

    @Test
    fun requireHttpsReturnsTheNormalizedAddress() {
        assertEquals(
            "https://example.com",
            BackendUrlPolicy.requireHttps("  https://example.com/  "),
        )
        assertEquals(
            "https://nex-companion-api.onrender.com",
            BackendUrlPolicy.requireHttps("https://nex-companion-api.onrender.com"),
        )
    }

    @Test
    fun requireHttpsThrowsForAnythingElse() {
        listOf(
            "",
            "   ",
            "http://example.com",
            "https://",
            "https://user:pass@example.com",
            "not a url at all",
            "https://example.com/" + "a".repeat(400),
        ).forEach { raw ->
            try {
                BackendUrlPolicy.requireHttps(raw)
                fail("'$raw' must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("rejected"))
                // The raw address is never repeated in the exception message either.
                assertFalse(expected.message.orEmpty().contains("pass@example"))
            }
        }
    }

    @Test
    fun userMessagesAreSafeToShowOnScreen() {
        listOf(
            "",
            "http://example.com",
            "https://",
            "https://user:pass@example.com",
            "https://exa mple.com",
            "https://example.com/" + "a".repeat(400),
        ).forEach { raw ->
            val result = BackendUrlPolicy.check(raw)
            assertTrue(result is BackendUrlPolicy.UrlCheck.Invalid)
            val message = (result as BackendUrlPolicy.UrlCheck.Invalid).userMessage
            assertTrue(message.isNotBlank())
            if (raw.isNotBlank()) {
                assertFalse("'$message' echoes the raw address", message.contains(raw))
            }
            listOf("Exception", "URI", "stack", "null").forEach { leak ->
                assertFalse("'$message' leaks '$leak'", message.contains(leak))
            }
        }
    }
}
