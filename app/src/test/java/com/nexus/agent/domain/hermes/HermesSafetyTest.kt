package com.nexus.agent.domain.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesSafetyTest {

    @Test
    fun `normalizes https endpoint with explicit port`() {
        val result = HermesSafety.normalizeEndpoint("https://host.example.com:8642")
        assertTrue(result.isSuccess)
        assertEquals("https://host.example.com:8642", result.getOrNull())
    }

    @Test
    fun `assumes https scheme when omitted`() {
        val result = HermesSafety.normalizeEndpoint("host.example.com:8642")
        assertTrue(result.isSuccess)
        assertEquals("https://host.example.com:8642", result.getOrNull())
    }

    @Test
    fun `drops path and query`() {
        val result = HermesSafety.normalizeEndpoint("https://host.example.com:8642/v1?x=1")
        assertTrue(result.isSuccess)
        assertEquals("https://host.example.com:8642", result.getOrNull())
    }

    @Test
    fun `allows plain http for loopback`() {
        val result = HermesSafety.normalizeEndpoint("http://127.0.0.1:8642")
        assertTrue(result.isSuccess)
        assertEquals("http://127.0.0.1:8642", result.getOrNull())
    }

    @Test
    fun `rejects plain http for non-loopback hosts`() {
        val result = HermesSafety.normalizeEndpoint("http://host.example.com:8642")
        assertTrue(result.isFailure)
        assertEquals(HermesSafety.ERR_HTTPS_REQUIRED, result.exceptionOrNull()?.message)
    }

    @Test
    fun `rejects empty endpoint`() {
        val result = HermesSafety.normalizeEndpoint("   ")
        assertTrue(result.isFailure)
        assertEquals(HermesSafety.ERR_EMPTY, result.exceptionOrNull()?.message)
    }

    @Test
    fun `rejects malformed endpoint`() {
        val result = HermesSafety.normalizeEndpoint("://bad url")
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects blank api key`() {
        val result = HermesSafety.validateApiKey("  ")
        assertTrue(result.isFailure)
        assertEquals(HermesSafety.ERR_NO_KEY, result.exceptionOrNull()?.message)
    }

    @Test
    fun `accepts non-blank api key`() {
        val result = HermesSafety.validateApiKey(" secret ")
        assertTrue(result.isSuccess)
        assertEquals("secret", result.getOrNull())
    }

    @Test
    fun `sanitizeForStorage strips userinfo and path`() {
        assertEquals(
            "https://host.example.com:8642",
            HermesSafety.sanitizeForStorage("https://user:pass@host.example.com:8642/v1?x=1")
        )
    }

    @Test
    fun `sanitizeForStorage keeps http for any host (no TLS enforcement)`() {
        // sanitizeForStorage only strips credentials; TLS is enforced by normalizeEndpoint at runtime.
        assertEquals(
            "http://host.example.com:8642",
            HermesSafety.sanitizeForStorage("http://user:pass@host.example.com:8642")
        )
    }

    @Test
    fun `sanitizeForStorage returns empty for blank input`() {
        assertEquals("", HermesSafety.sanitizeForStorage("   "))
    }

    @Test
    fun `sanitizeForStorage fails closed on unparseable input`() {
        // Unparseable input that could still carry userinfo must NOT be persisted as-is.
        assertEquals("", HermesSafety.sanitizeForStorage("http://user:pass@host:badport"))
    }
}
