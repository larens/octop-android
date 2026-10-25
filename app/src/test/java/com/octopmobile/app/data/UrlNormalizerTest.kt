package com.octopmobile.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlNormalizerTest {

    private fun valid(raw: String): UrlNormalizer.Outcome.Valid =
        UrlNormalizer.normalize(raw) as UrlNormalizer.Outcome.Valid

    private fun reason(raw: String): UrlNormalizer.Reason =
        (UrlNormalizer.normalize(raw) as UrlNormalizer.Outcome.Invalid).reason

    @Test
    fun `adds https when scheme is missing`() {
        assertEquals("https://octop.example.com", valid("octop.example.com").baseUrl)
    }

    @Test
    fun `keeps explicit http and flags it as cleartext`() {
        val outcome = valid("http://octop.example.com")
        assertEquals("http://octop.example.com", outcome.baseUrl)
        assertEquals(true, outcome.isCleartext)
    }

    @Test
    fun `strips trailing slash and path on root`() {
        assertEquals("https://octop.example.com", valid("https://octop.example.com/").baseUrl)
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("https://octop.example.com", valid("  octop.example.com  ").baseUrl)
    }

    @Test
    fun `keeps non default port in authority`() {
        val outcome = valid("https://octop.example.com:8443")
        assertEquals("octop.example.com:8443", outcome.authority)
    }

    @Test
    fun `omits default port from authority`() {
        assertEquals("octop.example.com", valid("https://octop.example.com:443").authority)
    }

    @Test
    fun `rejects empty input`() {
        assertEquals(UrlNormalizer.Reason.EMPTY, reason("   "))
    }

    @Test
    fun `rejects sub path deployments`() {
        assertEquals(UrlNormalizer.Reason.HAS_PATH, reason("https://example.com/octop"))
    }

    @Test
    fun `rejects unsupported scheme`() {
        assertEquals(UrlNormalizer.Reason.UNSUPPORTED_SCHEME, reason("ftp://example.com"))
    }

    @Test
    fun `rejects credentials in url`() {
        assertEquals(UrlNormalizer.Reason.MALFORMED, reason("https://user:pass@example.com"))
    }

    @Test
    fun `rejects scheme without host`() {
        assertEquals(UrlNormalizer.Reason.MALFORMED, reason("https://"))
    }
}
