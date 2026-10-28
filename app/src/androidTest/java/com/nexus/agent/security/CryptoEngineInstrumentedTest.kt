package com.nexus.agent.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * Instrumented tests for the real Android Keystore path. These run on a device or
 * emulator (not the JVM), because the Keystore provider only exists on Android.
 */
@RunWith(AndroidJUnit4::class)
class CryptoEngineInstrumentedTest {

    private fun newAlias(): String = "nexus_test_key_${System.nanoTime()}"

    @Test
    fun keyIsNonExportableAndRoundTrips() {
        val provider = AndroidKeystoreKeyProvider(newAlias())
        val key = provider.getOrCreateKey()
        assertNull("Android Keystore key must be non-exportable", key.encoded)

        val engine = CryptoEngine(provider)
        val plain = "NEXUS instrumented payload".toByteArray(Charsets.UTF_8)
        assertArrayEquals(plain, engine.decrypt(engine.encrypt(plain)))
    }

    @Test
    fun stringRoundTripSupportsUnicode() {
        val engine = CryptoEngine(AndroidKeystoreKeyProvider(newAlias()))
        val original = "\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645 \u2014 hello \u4E16\u754C \uD83D\uDD10"
        val encoded = engine.encryptString(original)
        assertFalse("ciphertext must not contain the plaintext", encoded.contains(original))
        assertEquals(original, engine.decryptString(encoded))
    }

    @Test
    fun invalidatedKeyFailsSafely() {
        val alias = newAlias()
        val provider = AndroidKeystoreKeyProvider(alias)
        val engine = CryptoEngine(provider)
        val encoded = engine.encrypt("data".toByteArray(Charsets.UTF_8))

        // Simulate key invalidation (e.g. lock-screen change / explicit wipe).
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        // The key is re-created on next use, so the old ciphertext can no longer be
        // decrypted — this must surface as a TamperDetectedException, not a crash.
        assertThrows(TamperDetectedException::class.java) { engine.decrypt(encoded) }
    }
}
