package com.nexus.agent.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CiphertextFormatTest {

    @Test
    fun encodeParseRoundTrip() {
        val iv = ByteArray(CiphertextFormat.IV_LENGTH) { it.toByte() }
        val ct = ByteArray(20) { (it + 1).toByte() }
        val encoded = CiphertextFormat.encode(iv, ct)
        assertEquals(1 + CiphertextFormat.IV_LENGTH + 20, encoded.size)
        assertEquals(CiphertextFormat.VERSION, encoded[0].toInt() and 0xFF)

        val parsed = CiphertextFormat.parse(encoded)
        assertEquals(CiphertextFormat.VERSION, parsed.version)
        assertArrayEquals(iv, parsed.iv)
        assertArrayEquals(ct, parsed.ciphertextAndTag)
    }

    @Test
    fun rejectsTooShortInput() {
        assertThrows(InvalidCiphertextException::class.java) {
            CiphertextFormat.parse(ByteArray(CiphertextFormat.MIN_LENGTH - 1))
        }
    }

    @Test
    fun rejectsUnknownVersion() {
        val buf = ByteArray(CiphertextFormat.MIN_LENGTH)
        buf[0] = 99
        assertThrows(InvalidCiphertextException::class.java) {
            CiphertextFormat.parse(buf)
        }
    }

    @Test
    fun encodeRejectsWrongIvLength() {
        assertThrows(IllegalArgumentException::class.java) {
            CiphertextFormat.encode(ByteArray(11), ByteArray(16))
        }
    }
}
