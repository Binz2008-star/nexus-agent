package com.nexus.agent.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexus.agent.security.CryptoException
import com.nexus.agent.security.KeyUnavailableException
import com.nexus.agent.security.SecretCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Result of reading the stored API key. Callers get an explicit, recoverable state
 * instead of a silent "no key" — the UI can distinguish "not configured" from
 * "corrupted" from "encryption key invalidated".
 */
sealed interface ApiKeyReadResult {
    data class Present(val value: String) : ApiKeyReadResult
    data object Missing : ApiKeyReadResult
    data object Corrupted : ApiKeyReadResult
    data object KeyUnavailable : ApiKeyReadResult
}

private val Context.nexusSecretsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nexus_secrets"
)

/**
 * Stores the DeepSeek API key as AES-GCM ciphertext in Preferences DataStore.
 * Only ciphertext is ever persisted — the plaintext key is never written to disk or
 * logged. Decryption failures are surfaced as [ApiKeyReadResult] states, never as a
 * crash or a silent empty result.
 */
class SecretStorageRepository(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher
) {
    /** Encrypts and stores [plaintext]. Throws [CryptoException] if encryption fails. */
    suspend fun saveApiKey(plaintext: String) {
        saveSecret(API_KEY_PREF_NAME, plaintext)
    }

    /** Reads and decrypts the stored key, distinguishing missing / corrupted / invalidated. */
    suspend fun readApiKey(): ApiKeyReadResult = readSecret(API_KEY_PREF_NAME)

    suspend fun clearApiKey() {
        clearSecret(API_KEY_PREF_NAME)
    }

    /** Generic encrypted-secret storage (used for the DeepSeek key and Neon connection strings). */
    suspend fun saveSecret(name: String, plaintext: String) {
        val ciphertext = cipher.encryptString(plaintext)
        dataStore.edit { it[stringPreferencesKey(name)] = ciphertext }
    }

    suspend fun readSecret(name: String): ApiKeyReadResult {
        val ciphertext = dataStore.data.map { it[stringPreferencesKey(name)] }.first()
            ?: return ApiKeyReadResult.Missing
        return try {
            ApiKeyReadResult.Present(cipher.decryptString(ciphertext))
        } catch (e: KeyUnavailableException) {
            ApiKeyReadResult.KeyUnavailable
        } catch (e: CryptoException) {
            ApiKeyReadResult.Corrupted
        }
    }

    suspend fun clearSecret(name: String) {
        dataStore.edit { it.remove(stringPreferencesKey(name)) }
    }

    companion object {
        const val API_KEY_PREF_NAME = "encrypted_deepseek_api_key"
        const val NEON_READ_URL_PREF = "encrypted_neon_read_url"
        const val NEON_WRITE_URL_PREF = "encrypted_neon_write_url"

        fun create(context: Context, cipher: SecretCipher): SecretStorageRepository =
            SecretStorageRepository(context.nexusSecretsDataStore, cipher)
    }
}
