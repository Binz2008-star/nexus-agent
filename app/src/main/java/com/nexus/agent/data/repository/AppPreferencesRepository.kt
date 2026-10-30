package com.nexus.agent.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nexus_app_preferences"
)

/**
 * Non-secret app preferences (system prompt, last model, etc.).
 * Separate from SecretStorageRepository which holds encrypted secrets.
 */
class AppPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) {
    private val systemPromptKey = stringPreferencesKey("system_prompt")
    private val lastModelKey = stringPreferencesKey("last_model")
    private val knowledgeBaseUrlKey = stringPreferencesKey("knowledge_base_url")
    private val mirrorEnabledKey = booleanPreferencesKey("mirror_enabled")

    val systemPrompt: Flow<String> = dataStore.data.map { it[systemPromptKey] ?: "" }
    val lastModel: Flow<String> = dataStore.data.map { it[lastModelKey] ?: "deepseek-flash" }
    val knowledgeBaseUrl: Flow<String> = dataStore.data.map { it[knowledgeBaseUrlKey] ?: "" }
    // Opt-in (default false): mirroring full transcripts to Neon is off unless the user enables it.
    val mirrorEnabled: Flow<Boolean> = dataStore.data.map { it[mirrorEnabledKey] ?: false }

    suspend fun setSystemPrompt(prompt: String) {
        dataStore.edit { prefs ->
            if (prompt.isBlank()) {
                prefs.remove(systemPromptKey)
            } else {
                prefs[systemPromptKey] = prompt.trim()
            }
        }
    }

    suspend fun setLastModel(model: String) {
        dataStore.edit { it[lastModelKey] = model }
    }

    suspend fun setKnowledgeBaseUrl(url: String) {
        dataStore.edit { prefs ->
            if (url.isBlank()) {
                prefs.remove(knowledgeBaseUrlKey)
            } else {
                prefs[knowledgeBaseUrlKey] = url.trim()
            }
        }
    }

    suspend fun setMirrorEnabled(enabled: Boolean) {
        dataStore.edit { it[mirrorEnabledKey] = enabled }
    }

    companion object {
        fun create(context: Context): AppPreferencesRepository =
            AppPreferencesRepository(context.appPreferencesDataStore)
    }
}
