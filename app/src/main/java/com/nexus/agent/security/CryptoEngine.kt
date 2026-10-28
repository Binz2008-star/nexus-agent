package com.nexus.agent.security

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException

/**
 * Facade over [AesGcmCipher] and a [KeyProvider]. Maps low-level JCE/Keystore failures
 * onto explicit NEXUS exceptions so callers never have to reason about raw internals.
 */
class CryptoEngine(
    private val keyProvider: KeyProvider,
    private val cipher: AesGcmCipher = AesGcmCipher()
) : SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray = try {
        cipher.encrypt(plaintext, keyProvider.getOrCreateKey())
    } catch (e: CryptoException) {
        throw e
    } catch (e: KeyStoreException) {
        throw KeyUnavailableException("keystore unavailable during encryption", e)
    } catch (e: UnrecoverableKeyException) {
        throw KeyUnavailableException("key unrecoverable or invalidated during encryption", e)
    } catch (e: GeneralSecurityException) {
        throw CryptoException("encryption failed: ${e.javaClass.simpleName} (${e.message})", e)
    }

    fun decrypt(encoded: ByteArray): ByteArray = try {
        cipher.decrypt(encoded, keyProvider.getOrCreateKey())
    } catch (e: CryptoException) {
        throw e
    } catch (e: KeyStoreException) {
        throw KeyUnavailableException("keystore unavailable during decryption", e)
    } catch (e: UnrecoverableKeyException) {
        throw KeyUnavailableException("key unrecoverable or invalidated during decryption", e)
    } catch (e: GeneralSecurityException) {
        throw CryptoException("decryption failed: ${e.javaClass.simpleName} (${e.message})", e)
    }

    override fun encryptString(value: String): String =
        Base64.encodeToString(encrypt(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    override fun decryptString(value: String): String {
        val bytes = try {
            Base64.decode(value, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw InvalidCiphertextException("input is not valid Base64")
        }
        return String(decrypt(bytes), Charsets.UTF_8)
    }
}
