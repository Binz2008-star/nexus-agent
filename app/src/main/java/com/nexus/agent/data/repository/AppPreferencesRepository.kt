package com.nexus.agent.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    val systemPrompt: Flow<String> = dataStore.data.map { it[systemPromptKey] ?: "" }
    val lastModel: Flow<String> = dataStore.data.map { it[lastModelKey] ?: "deepseek-flash" }
    val knowledgeBaseUrl: Flow<String> = dataStore.data.map { it[knowledgeBaseUrlKey] ?: "" }

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

    companion object {
        fun create(context: Context): AppPreferencesRepository =
            AppPreferencesRepository(context.appPreferencesDataStore)
    }
}
