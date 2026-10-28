package com.nexus.agent.security

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a fresh random 12-byte IV per encryption and a 128-bit
 * authentication tag. Pure JCE, so the same code runs on Android and the JVM
 * (which is what makes the format/round-trip/tamper unit tests possible).
 */
class AesGcmCipher {
    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(CiphertextFormat.IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return CiphertextFormat.encode(iv, cipher.doFinal(plaintext))
    }

    fun decrypt(encoded: ByteArray, key: SecretKey): ByteArray {
        val parsed = CiphertextFormat.parse(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, parsed.iv))
            cipher.doFinal(parsed.ciphertextAndTag)
        } catch (e: AEADBadTagException) {
            throw TamperDetectedException(
                "GCM authentication failed: ciphertext was tampered with or the key is wrong",
                e
            )
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
