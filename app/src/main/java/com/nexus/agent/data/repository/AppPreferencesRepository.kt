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
    private val analyticsEnabledKey = booleanPreferencesKey("analytics_enabled")
    private val mashaaerEnabledKey = booleanPreferencesKey("mashaaer_emotion_enabled")
    private val dialectKey = stringPreferencesKey("preferred_dialect")
    private val toneKey = stringPreferencesKey("preferred_tone")
    private val hermesEndpointKey = stringPreferencesKey("hermes_endpoint")
    private val lastEngineKey = stringPreferencesKey("last_engine")

    val systemPrompt: Flow<String> = dataStore.data.map { it[systemPromptKey] ?: "" }
    val lastModel: Flow<String> = dataStore.data.map { it[lastModelKey] ?: "deepseek-flash" }
    val knowledgeBaseUrl: Flow<String> = dataStore.data.map { it[knowledgeBaseUrlKey] ?: "" }
    // Opt-in (default false): mirroring full transcripts to Neon is off unless the user enables it.
    val mirrorEnabled: Flow<Boolean> = dataStore.data.map { it[mirrorEnabledKey] ?: false }
    // No consent means no telemetry. Stored locally, separate from chat mirroring.
    val analyticsEnabled: Flow<Boolean> = dataStore.data.map { it[analyticsEnabledKey] ?: false }
    // Explicit opt-in. Only transient per-message emotional cues are computed; never persisted.
    val mashaaerEmotionEnabled: Flow<Boolean> =
        dataStore.data.map { it[mashaaerEnabledKey] ?: false }
    val preferredDialect: Flow<String> = dataStore.data.map { it[dialectKey] ?: "auto" }
    val preferredTone: Flow<String> = dataStore.data.map { it[toneKey] ?: "friendly" }
    // Non-secret Hermes API server origin (e.g. https://host:8642). The bearer key lives encrypted
    // in SecretStorageRepository; only the host address is stored here.
    val hermesEndpoint: Flow<String> = dataStore.data.map { it[hermesEndpointKey] ?: "" }
    // Last-selected AI engine id ("deepseek" | "hermes"); defaults to DeepSeek.
    val lastEngine: Flow<String> = dataStore.data.map { it[lastEngineKey] ?: "deepseek" }

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

    suspend fun setAnalyticsEnabled(enabled: Boolean) {
        dataStore.edit { it[analyticsEnabledKey] = enabled }
    }

    suspend fun setMashaaerEmotionEnabled(enabled: Boolean) {
        dataStore.edit { it[mashaaerEnabledKey] = enabled }
    }

    suspend fun setPreferredDialect(dialect: String) {
        require(dialect in setOf("auto", "gulf", "levant", "egypt", "maghreb",
            "iraq", "sudan", "yemen", "en-us", "en-uk"))
        dataStore.edit { it[dialectKey] = dialect }
    }

    suspend fun setPreferredTone(tone: String) {
        require(tone in setOf("calm", "emotional", "assertive", "friendly", "professional"))
        dataStore.edit { it[toneKey] = tone }
    }

    suspend fun setHermesEndpoint(url: String) {
        dataStore.edit { prefs ->
            if (url.isBlank()) {
                prefs.remove(hermesEndpointKey)
            } else {
                prefs[hermesEndpointKey] = url.trim()
            }
        }
    }

    suspend fun setLastEngine(engineId: String) {
        dataStore.edit { it[lastEngineKey] = engineId }
    }

    companion object {
        fun create(context: Context): AppPreferencesRepository =
            AppPreferencesRepository(context.appPreferencesDataStore)
    }
}
