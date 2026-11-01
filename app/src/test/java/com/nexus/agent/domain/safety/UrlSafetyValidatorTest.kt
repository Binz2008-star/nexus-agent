package com.nexus.agent.domain.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class UrlSafetyValidatorTest {

    @Test
    fun `ipv4 private and reserved are not public`() {
        assertFalse(UrlSafetyValidator.isPublicIpv4(byteArrayOf(10, 0, 0, 1)))
        assertFalse(UrlSafetyValidator.isPublicIpv4(byteArrayOf(127, 0, 0, 1)))
        assertFalse(UrlSafetyValidator.isPublicIpv4(byteArrayOf(192, 168, 1, 1)))
        assertFalse(UrlSafetyValidator.isPublicIpv4(byteArrayOf(172, 16, 0, 1)))
        assertFalse(UrlSafetyValidator.isPublicIpv4(byteArrayOf(169, 254, 169, 254))) // metadata
        assertFalse(UrlSafetyValidator.isPublicIpv4(byteArrayOf(0, 0, 0, 0)))
        assertFalse(UrlSafetyValidator.isPublicIpv4(byteArrayOf(224, 0, 0, 1))) // multicast
        assertFalse(UrlSafetyValidator.isPublicIpv4(byteArrayOf(100, 64, 0, 1))) // CGNAT
        assertFalse(UrlSafetyValidator.isPublicIpv4(byteArrayOf(192, 0, 2, 1))) // TEST-NET
    }

    @Test
    fun `ipv4 public passes`() {
        assertTrue(UrlSafetyValidator.isPublicIpv4(byteArrayOf(8, 8, 8, 8)))
        assertTrue(UrlSafetyValidator.isPublicIpv4(byteArrayOf(1, 1, 1, 1)))
        assertTrue(UrlSafetyValidator.isPublicIpv4(byteArrayOf(93, 184, 216, 34)))
    }

    @Test
    fun `ipv6 unspecified loopback linklocal uniquelocal multicast blocked`() {
        assertFalse(UrlSafetyValidator.isPublicIpv6(ByteArray(16) { 0 })) // ::
        assertFalse(
            UrlSafetyValidator.isPublicIpv6(
                byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
            )
        ) // ::1
        assertFalse(
            UrlSafetyValidator.isPublicIpv6(
                byteArrayOf(0xFE.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
            )
        ) // fe80::1
        assertFalse(
            UrlSafetyValidator.isPublicIpv6(
                byteArrayOf(0xFC.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
            )
        ) // fc00::1
        assertFalse(
            UrlSafetyValidator.isPublicIpv6(
                byteArrayOf(0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
            )
        ) // ff00::1
    }

    @Test
    fun `ipv6 public passes`() {
        val b = byteArrayOf(0x26, 0x06, 0x47, 0x00, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11)
        assertTrue(UrlSafetyValidator.isPublicIpv6(b)) // 2606:4700:4700::1111
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
        val validator = UrlSafetyValidator(resolver = { listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))) })
        val r = validator.validate("http://foo.example.com")
        assertTrue(r.isFailure)
        assertEquals(UrlSafetyValidator.ERR_BLOCKED, r.exceptionOrNull()?.message)
    }

    @Test
    fun `validate accepts public resolved ip`() {
        val validator = UrlSafetyValidator(resolver = { listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) })
        val r = validator.validate("https://example.com/page")
        assertTrue(r.isSuccess)
        assertEquals("example.com", r.getOrNull()?.host)
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
