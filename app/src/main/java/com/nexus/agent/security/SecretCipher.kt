package com.nexus.agent.security

/**
 * Minimal string-level encryption contract implemented by [CryptoEngine]. Keeping this
 * behind an interface lets data-layer repositories be tested with a safe test double
 * instead of a real Keystore-backed engine.
 */
interface SecretCipher {
    /** [context] is used as GCM additional authenticated data, binding the ciphertext to its slot. */
    fun encryptString(value: String, context: String): String
    fun decryptString(value: String, context: String): String
}
