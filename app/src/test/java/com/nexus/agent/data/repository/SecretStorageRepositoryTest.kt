package com.nexus.agent.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexus.agent.security.CryptoException
import com.nexus.agent.security.InvalidCiphertextException
import com.nexus.agent.security.KeyUnavailableException
import com.nexus.agent.security.SecretCipher
import com.nexus.agent.security.TamperDetectedException
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM tests for [SecretStorageRepository] using a real Preferences DataStore backed by a
 * temp file and a reversible [FakeCipher] test double (no real Keystore, no real key).
 */
class SecretStorageRepositoryTest {

    private class FakeCipher(
        private val encryptError: CryptoException? = null,
        private val decryptError: CryptoException? = null
    ) : SecretCipher {
        override fun encryptString(value: String): String {
            encryptError?.let { throw it }
            return "enc:$value"
        }

        override fun decryptString(value: String): String {
            decryptError?.let { throw it }
            if (!value.startsWith("enc:")) throw InvalidCiphertextException("not ciphertext")
            return value.removePrefix("enc:")
        }
    }

    private fun createDataStore(): Pair<DataStore<Preferences>, CoroutineScope> {
        val file = File.createTempFile("nexus-secret-test", ".preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        return dataStore to scope
    }

    @Test
    fun saveAndReadRoundTrip() {
        val (dataStore, scope) = createDataStore()
        try {
            runBlocking {
                val repo = SecretStorageRepository(dataStore, FakeCipher())
                repo.saveApiKey("sk-test-plain-123")
                assertEquals(ApiKeyReadResult.Present("sk-test-plain-123"), repo.readApiKey())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun missingKeyReturnsMissing() {
        val (dataStore, scope) = createDataStore()
        try {
            runBlocking {
                val repo = SecretStorageRepository(dataStore, FakeCipher())
                assertEquals(ApiKeyReadResult.Missing, repo.readApiKey())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clearRemovesKey() {
        val (dataStore, scope) = createDataStore()
        try {
            runBlocking {
                val repo = SecretStorageRepository(dataStore, FakeCipher())
                repo.saveApiKey("sk-test-456")
                repo.clearApiKey()
                assertEquals(ApiKeyReadResult.Missing, repo.readApiKey())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun plaintextIsNotStoredVerbatim() {
        val (dataStore, scope) = createDataStore()
        try {
            runBlocking {
                val repo = SecretStorageRepository(dataStore, FakeCipher())
                repo.saveApiKey("sk-secret-value")
                val stored = dataStore.data
                    .map { it[stringPreferencesKey(SecretStorageRepository.API_KEY_PREF_NAME)] }
                    .first()
                assertNotEquals("sk-secret-value", stored)
                assertEquals("enc:sk-secret-value", stored)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun tamperedCiphertextReturnsCorrupted() {
        val (dataStore, scope) = createDataStore()
        try {
            runBlocking {
                val cipher = FakeCipher(decryptError = TamperDetectedException("tampered"))
                val repo = SecretStorageRepository(dataStore, cipher)
                repo.saveApiKey("sk-test-789")
                assertEquals(ApiKeyReadResult.Corrupted, repo.readApiKey())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun malformedCiphertextReturnsCorrupted() {
        val (dataStore, scope) = createDataStore()
        try {
            runBlocking {
                val cipher = FakeCipher(decryptError = InvalidCiphertextException("malformed"))
                val repo = SecretStorageRepository(dataStore, cipher)
                repo.saveApiKey("sk-test-000")
                assertEquals(ApiKeyReadResult.Corrupted, repo.readApiKey())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun invalidatedKeyReturnsKeyUnavailable() {
        val (dataStore, scope) = createDataStore()
        try {
            runBlocking {
                val cipher = FakeCipher(decryptError = KeyUnavailableException("key gone"))
                val repo = SecretStorageRepository(dataStore, cipher)
                repo.saveApiKey("sk-test-abc")
                assertEquals(ApiKeyReadResult.KeyUnavailable, repo.readApiKey())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun encryptionFailurePropagates() {
        val (dataStore, scope) = createDataStore()
        try {
            val repo = SecretStorageRepository(
                dataStore,
                FakeCipher(encryptError = CryptoException("boom"))
            )
            assertThrows(CryptoException::class.java) {
                runBlocking { repo.saveApiKey("sk-test-xyz") }
            }
        } finally {
            scope.cancel()
        }
    }
}
