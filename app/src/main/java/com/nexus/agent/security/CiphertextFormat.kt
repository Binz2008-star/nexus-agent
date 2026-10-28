package com.nexus.agent.security

/**
 * Versioned ciphertext layout. Version 1:
 *
 * ```
 * [version: 1 byte] [IV: 12 bytes] [ciphertext || 128-bit GCM tag]
 * ```
 *
 * The version byte allows future migration to a different algorithm/format without
 * ambiguity. Pure Kotlin — no Android dependencies, so it is JVM unit-testable.
 */
object CiphertextFormat {
    const val VERSION: Int = 1
    const val IV_LENGTH: Int = 12
    const val TAG_LENGTH: Int = 16
    const val MIN_LENGTH: Int = 1 + IV_LENGTH + TAG_LENGTH // 29

    data class Parsed(val version: Int, val iv: ByteArray, val ciphertextAndTag: ByteArray)

    fun encode(iv: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        require(iv.size == IV_LENGTH) { "IV must be $IV_LENGTH bytes, got ${iv.size}" }
        return byteArrayOf(VERSION.toByte()) + iv + ciphertextAndTag
    }

    fun parse(encoded: ByteArray): Parsed {
        if (encoded.size < MIN_LENGTH) {
            throw InvalidCiphertextException(
                "ciphertext too short: ${encoded.size} bytes (minimum $MIN_LENGTH)"
            )
        }
        val version = encoded[0].toInt() and 0xFF
        if (version != VERSION) {
            throw InvalidCiphertextException("unsupported ciphertext version: $version")
        }
        val iv = encoded.copyOfRange(1, 1 + IV_LENGTH)
        val ciphertextAndTag = encoded.copyOfRange(1 + IV_LENGTH, encoded.size)
        return Parsed(version, iv, ciphertextAndTag)
    }
}
