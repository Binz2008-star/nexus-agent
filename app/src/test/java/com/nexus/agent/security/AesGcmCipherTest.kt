package com.nexus.agent.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * JVM-only tests for the AES-GCM + versioned-format logic. The key is an in-memory
 * JCE key (the Android Keystore path is covered by the instrumented tests).
 */
class AesGcmCipherTest {

    private lateinit var cipher: AesGcmCipher
    private lateinit var key: SecretKey

    @Before
    fun setUp() {
        cipher = AesGcmCipher()
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    @Test
    fun roundTrip() {
        val plain = "NEXUS secret message".toByteArray(Charsets.UTF_8)
        val encoded = cipher.encrypt(plain, key)
        assertTrue(encoded.size > plain.size)
        assertArrayEquals(plain, cipher.decrypt(encoded, key))
    }

    @Test
    fun emptyPlaintextRoundTrips() {
        val encoded = cipher.encrypt(ByteArray(0), key)
        assertArrayEquals(ByteArray(0), cipher.decrypt(encoded, key))
    }

    @Test
    fun randomIvProducesDistinctCiphertexts() {
        val plain = "same plaintext".toByteArray(Charsets.UTF_8)
        assertFalse(cipher.encrypt(plain, key).contentEquals(cipher.encrypt(plain, key)))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val encoded = cipher.encrypt("tamper me".toByteArray(Charsets.UTF_8), key)
        encoded[encoded.size - 1] = (encoded[encoded.size - 1].toInt() xor 0xFF).toByte()
        assertThrows(TamperDetectedException::class.java) { cipher.decrypt(encoded, key) }
    }

    @Test
    fun wrongKeyIsRejected() {
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val encoded = cipher.encrypt("secret".toByteArray(Charsets.UTF_8), key)
        assertThrows(TamperDetectedException::class.java) { cipher.decrypt(encoded, other) }
    }

    @Test
    fun malformedInputIsRejected() {
        val encoded = cipher.encrypt("secret".toByteArray(Charsets.UTF_8), key)
        val tooShort = encoded.copyOf(CiphertextFormat.MIN_LENGTH - 1)
        assertThrows(InvalidCiphertextException::class.java) { cipher.decrypt(tooShort, key) }
    }
}
