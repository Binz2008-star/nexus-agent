package com.nexus.agent.security

/**
 * Minimal string-level encryption contract implemented by [CryptoEngine]. Keeping this
 * behind an interface lets data-layer repositories be tested with a safe test double
 * instead of a real Keystore-backed engine.
 */
interface SecretCipher {
    fun encryptString(value: String): String
    fun decryptString(value: String): String
}
