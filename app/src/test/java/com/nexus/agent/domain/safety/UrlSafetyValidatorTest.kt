package com.nexus.agent.domain.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class UrlSafetyValidatorTest {

    /** Builds a ByteArray from unsigned octets (each Int is masked to a byte). */
    private fun bytes(vararg octets: Int): ByteArray =
        ByteArray(octets.size) { octets[it].toByte() }

    @Test
    fun `ipv4 private and reserved are not public`() {
        assertFalse(UrlSafetyValidator.isPublicIpv4(bytes(10, 0, 0, 1)))
        assertFalse(UrlSafetyValidator.isPublicIpv4(bytes(127, 0, 0, 1)))
        assertFalse(UrlSafetyValidator.isPublicIpv4(bytes(192, 168, 1, 1)))
        assertFalse(UrlSafetyValidator.isPublicIpv4(bytes(172, 16, 0, 1)))
        assertFalse(UrlSafetyValidator.isPublicIpv4(bytes(169, 254, 169, 254))) // metadata
        assertFalse(UrlSafetyValidator.isPublicIpv4(bytes(0, 0, 0, 0)))
        assertFalse(UrlSafetyValidator.isPublicIpv4(bytes(224, 0, 0, 1))) // multicast
        assertFalse(UrlSafetyValidator.isPublicIpv4(bytes(100, 64, 0, 1))) // CGNAT
        assertFalse(UrlSafetyValidator.isPublicIpv4(bytes(192, 0, 2, 1))) // TEST-NET
        assertFalse(UrlSafetyValidator.isPublicIpv4(bytes(192, 88, 99, 1))) // 6to4 relay anycast
    }

    @Test
    fun `ipv4 public passes`() {
        assertTrue(UrlSafetyValidator.isPublicIpv4(bytes(8, 8, 8, 8)))
        assertTrue(UrlSafetyValidator.isPublicIpv4(bytes(1, 1, 1, 1)))
        assertTrue(UrlSafetyValidator.isPublicIpv4(bytes(93, 184, 216, 34)))
    }

    @Test
    fun `ipv6 unspecified loopback linklocal uniquelocal multicast blocked`() {
        assertFalse(UrlSafetyValidator.isPublicIpv6(ByteArray(16) { 0 })) // ::
        assertFalse(
            UrlSafetyValidator.isPublicIpv6(bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
        ) // ::1
        assertFalse(
            UrlSafetyValidator.isPublicIpv6(bytes(0xFE, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
        ) // fe80::1
        assertFalse(
            UrlSafetyValidator.isPublicIpv6(bytes(0xFC, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
        ) // fc00::1
        assertFalse(
            UrlSafetyValidator.isPublicIpv6(bytes(0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
        ) // ff00::1
        assertFalse(
            UrlSafetyValidator.isPublicIpv6(bytes(0xFE, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
        ) // fe00::1 (IETF reserved)
        assertFalse(
            UrlSafetyValidator.isPublicIpv6(bytes(0x20, 0x02, 0x7F, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        ) // 2002:7f00:1:: (6to4 with embedded 127.0.0.1)
        assertFalse(
            UrlSafetyValidator.isPublicIpv6(bytes(0x00, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0, 0x7F, 0, 0, 1))
        ) // 64:ff9b::7f00:1 (NAT64 with embedded 127.0.0.1)
    }

    @Test
    fun `ipv6 public passes`() {
        // 2606:4700:4700::1111 (Cloudflare)
        val b = bytes(0x26, 0x06, 0x47, 0x00, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11)
        assertTrue(UrlSafetyValidator.isPublicIpv6(b))
        // 6to4 with a public embedded IPv4 (8.8.8.8) is treated as public
        assertTrue(
            UrlSafetyValidator.isPublicIpv6(bytes(0x20, 0x02, 0x08, 0x08, 0x08, 0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        )
    }

    @Test
    fun `blocked hostnames`() {
        assertTrue(UrlSafetyValidator.isBlockedHostname("localhost"))
        assertTrue(UrlSafetyValidator.isBlockedHostname("foo.local"))
        assertTrue(UrlSafetyValidator.isBlockedHostname("db.internal"))
        assertTrue(UrlSafetyValidator.isBlockedHostname("printer.home"))
        assertFalse(UrlSafetyValidator.isBlockedHostname("example.com"))
    }

    @Test
    fun `validate blocks private resolved ip`() {
        val validator = UrlSafetyValidator(resolver = { listOf(InetAddress.getByAddress(bytes(10, 0, 0, 1))) })
        val r = validator.validate("http://foo.example.com")
        assertTrue(r.isFailure)
        assertEquals(UrlSafetyValidator.ERR_BLOCKED, r.exceptionOrNull()?.message)
    }

    @Test
    fun `validate accepts public resolved ip and returns pinned ips`() {
        val validator = UrlSafetyValidator(resolver = { listOf(InetAddress.getByAddress(bytes(8, 8, 8, 8))) })
        val r = validator.validate("https://example.com/page")
        assertTrue(r.isSuccess)
        assertEquals("example.com", r.getOrNull()?.url?.host)
        assertEquals(1, r.getOrNull()?.ips?.size)
    }

    @Test
    fun `validate rejects malformed and non-http urls`() {
        val validator = UrlSafetyValidator(resolver = { emptyList() })
        assertTrue(validator.validate("not a url").isFailure)
        assertTrue(validator.validate("file:///etc/passwd").isFailure)
        assertEquals(UrlSafetyValidator.ERR_MALFORMED, validator.validate("not a url").exceptionOrNull()?.message)
    }

    @Test
    fun `validate fails closed when resolution returns nothing`() {
        val validator = UrlSafetyValidator(resolver = { emptyList() })
        val r = validator.validate("https://example.com")
        assertTrue(r.isFailure)
        assertEquals(UrlSafetyValidator.ERR_UNRESOLVED, r.exceptionOrNull()?.message)
    }
}
