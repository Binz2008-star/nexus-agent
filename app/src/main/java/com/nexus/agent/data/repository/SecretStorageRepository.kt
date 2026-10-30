package com.nexus.agent.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexus.agent.domain.connectors.PendingOAuthSession
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

    // ---- Google connector token (encrypted) + email (identifier, non-secret) ----

    /** Encrypts and stores the Google OAuth refresh token. Never stored in plaintext. */
    suspend fun saveGoogleRefreshToken(plaintext: String) =
        saveSecret(GOOGLE_REFRESH_TOKEN_PREF, plaintext)

    /** Reads/decrypts the Google refresh token. Reuses [ApiKeyReadResult] semantics. */
    suspend fun readGoogleRefreshToken(): ApiKeyReadResult = readSecret(GOOGLE_REFRESH_TOKEN_PREF)

    suspend fun clearGoogleRefreshToken() {
        clearSecret(GOOGLE_REFRESH_TOKEN_PREF)
    }

    /** The connected Google account's email (an identifier, not a credential). */
    suspend fun saveGoogleEmail(email: String) {
        dataStore.edit { it[stringPreferencesKey(GOOGLE_EMAIL_PREF)] = email.trim() }
    }

    suspend fun readGoogleEmail(): String? =
        dataStore.data.map { it[stringPreferencesKey(GOOGLE_EMAIL_PREF)] }.first()

    suspend fun clearGoogleEmail() {
        dataStore.edit { it.remove(stringPreferencesKey(GOOGLE_EMAIL_PREF)) }
    }

    // ---- Pending OAuth authorization (cold-start redirect recovery) ----

    /**
     * Persists an in-flight OAuth authorization (CSRF state + PKCE verifier + creation time)
     * so a redirect that arrives after process death can still be validated and exchanged.
     */
    suspend fun savePendingOAuth(state: String, verifier: String, createdAtMs: Long) {
        saveSecret(PENDING_OAUTH_STATE_PREF, state)
        saveSecret(PENDING_OAUTH_VERIFIER_PREF, verifier)
        dataStore.edit { it[longPreferencesKey(PENDING_OAUTH_CREATED_AT_PREF)] = createdAtMs }
    }

    suspend fun readPendingOAuth(): PendingOAuthSession? {
        val state = (readSecret(PENDING_OAUTH_STATE_PREF) as? ApiKeyReadResult.Present)?.value
            ?: return null
        val verifier = (readSecret(PENDING_OAUTH_VERIFIER_PREF) as? ApiKeyReadResult.Present)?.value
            ?: return null
        val createdAt = dataStore.data.map { it[longPreferencesKey(PENDING_OAUTH_CREATED_AT_PREF)] }.first()
            ?: return null
        return PendingOAuthSession(state, verifier, createdAt)
    }

    suspend fun clearPendingOAuth() {
        clearSecret(PENDING_OAUTH_STATE_PREF)
        clearSecret(PENDING_OAUTH_VERIFIER_PREF)
        dataStore.edit { it.remove(longPreferencesKey(PENDING_OAUTH_CREATED_AT_PREF)) }
    }

    companion object {
        const val API_KEY_PREF_NAME = "encrypted_deepseek_api_key"
        const val NEON_READ_URL_PREF = "encrypted_neon_read_url"
        const val NEON_WRITE_URL_PREF = "encrypted_neon_write_url"
        const val GOOGLE_REFRESH_TOKEN_PREF = "encrypted_google_refresh_token"
        const val GOOGLE_EMAIL_PREF = "google_email"
        const val PENDING_OAUTH_STATE_PREF = "encrypted_pending_oauth_state"
        const val PENDING_OAUTH_VERIFIER_PREF = "encrypted_pending_oauth_verifier"
        const val PENDING_OAUTH_CREATED_AT_PREF = "pending_oauth_created_at"

        fun create(context: Context, cipher: SecretCipher): SecretStorageRepository =
            SecretStorageRepository(context.nexusSecretsDataStore, cipher)
    }
}
