package com.nexus.agent.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    private val openRouterFallbackEnabledKey = booleanPreferencesKey("openrouter_free_fallback_enabled")
    private val knowledgeBaseUrlKey = stringPreferencesKey("knowledge_base_url")
    private val mirrorEnabledKey = booleanPreferencesKey("mirror_enabled")
    private val analyticsEnabledKey = booleanPreferencesKey("analytics_enabled")
    private val mashaaerEnabledKey = booleanPreferencesKey("mashaaer_emotion_enabled")
    private val dialectKey = stringPreferencesKey("preferred_dialect")
    private val toneKey = stringPreferencesKey("preferred_tone")
    private val hermesEndpointKey = stringPreferencesKey("hermes_endpoint")
    private val lastEngineKey = stringPreferencesKey("last_engine")
    private val neonBackendUrlKey = stringPreferencesKey("neon_backend_url")
    private val useNeonBackendKey = booleanPreferencesKey("use_neon_backend")
    private val lastConversationIdKey = longPreferencesKey("last_conversation_id")
    private val softModeEnabledKey = booleanPreferencesKey("soft_mode_enabled")
    private val ttsEnabledKey = booleanPreferencesKey("tts_enabled")
    private val ttsVoiceIdKey = stringPreferencesKey("tts_voice_id")
    private val ttsEnginePackageKey = stringPreferencesKey("tts_engine_package")
    private val ttsSpeechRateKey = floatPreferencesKey("tts_speech_rate")
    private val ttsPitchKey = floatPreferencesKey("tts_pitch")
    private val ttsAutoplayKey = booleanPreferencesKey("tts_autoplay")
    private val voiceInputOfflineOnlyKey = booleanPreferencesKey("voice_input_offline_only")

    val systemPrompt: Flow<String> = dataStore.data.map { it[systemPromptKey] ?: "" }
    val lastModel: Flow<String> = dataStore.data.map { it[lastModelKey] ?: "deepseek-flash" }
    // Default OFF: user must agree before moving conversations to a third-party provider.
    val openRouterFallbackEnabled: Flow<Boolean> =
        dataStore.data.map { it[openRouterFallbackEnabledKey] ?: false }
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
    // Non-secret Neon backend origin (e.g. https://nexus-backend.example.com). The bearer key lives
    // encrypted in SecretStorageRepository; only the host address is stored here.
    val neonBackendUrl: Flow<String> = dataStore.data.map { it[neonBackendUrlKey] ?: "" }
    // Feature flag: route Neon reads/writes through the HTTPS backend instead of direct JDBC.
    // Default false — JDBC stays the default until the backend is proven equivalent.
    val useNeonBackend: Flow<Boolean> = dataStore.data.map { it[useNeonBackendKey] ?: false }
    // Last active conversation id (nonsecret); -1 = none. Restored on cold start so the visible
    // chat survives process death.
    val lastConversationId: Flow<Long> = dataStore.data.map { it[lastConversationIdKey] ?: -1L }
    // Mashaaer Good Mode (الوضع الحنون): opt-in soft response persona. Default OFF.
    val softModeEnabled: Flow<Boolean> = dataStore.data.map { it[softModeEnabledKey] ?: false }
    // ---- Voice (Issue #24) ----
    val ttsEnabled: Flow<Boolean> = dataStore.data.map { it[ttsEnabledKey] ?: false }
    val ttsVoiceId: Flow<String> = dataStore.data.map { it[ttsVoiceIdKey] ?: "" }
    val ttsEnginePackage: Flow<String> = dataStore.data.map { it[ttsEnginePackageKey] ?: "" }
    val ttsSpeechRate: Flow<Float> = dataStore.data.map { it[ttsSpeechRateKey] ?: 1.0f }
    val ttsPitch: Flow<Float> = dataStore.data.map { it[ttsPitchKey] ?: 1.0f }
    val ttsAutoplay: Flow<Boolean> = dataStore.data.map { it[ttsAutoplayKey] ?: false }
    // Dictation privacy: default true so audio can never leave the device by surprise. Only an
    // explicit opt-out allows the platform (possibly network) recognizer.
    val voiceInputOfflineOnly: Flow<Boolean> =
        dataStore.data.map { it[voiceInputOfflineOnlyKey] ?: true }

    suspend fun setSystemPrompt(prompt: String) {
        dataStore.edit { prefs ->
            if (prompt.isBlank()) {
                prefs.remove(systemPromptKey)
            } else {
                prefs[systemPromptKey] = prompt.trim()
            }
        }
    }

    suspend fun setOpenRouterFallbackEnabled(enabled: Boolean) {
        dataStore.edit { it[openRouterFallbackEnabledKey] = enabled }
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

    suspend fun setNeonBackendUrl(url: String) {
        dataStore.edit { prefs ->
            if (url.isBlank()) {
                prefs.remove(neonBackendUrlKey)
            } else {
                prefs[neonBackendUrlKey] = url.trim()
            }
        }
    }

    suspend fun setUseNeonBackend(enabled: Boolean) {
        dataStore.edit { it[useNeonBackendKey] = enabled }
    }

    suspend fun setLastConversationId(id: Long) {
        dataStore.edit { it[lastConversationIdKey] = id }
    }

    suspend fun setSoftModeEnabled(enabled: Boolean) {
        dataStore.edit { it[softModeEnabledKey] = enabled }
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[ttsEnabledKey] = enabled }
    }

    suspend fun setTtsVoiceId(id: String) {
        dataStore.edit { it[ttsVoiceIdKey] = id }
    }

    suspend fun setTtsEnginePackage(pkg: String) {
        dataStore.edit { it[ttsEnginePackageKey] = pkg }
    }

    suspend fun setTtsSpeechRate(rate: Float) {
        dataStore.edit { it[ttsSpeechRateKey] = rate }
    }

    suspend fun setTtsPitch(pitch: Float) {
        dataStore.edit { it[ttsPitchKey] = pitch }
    }

    suspend fun setTtsAutoplay(autoplay: Boolean) {
        dataStore.edit { it[ttsAutoplayKey] = autoplay }
    }

    suspend fun setVoiceInputOfflineOnly(offlineOnly: Boolean) {
        dataStore.edit { it[voiceInputOfflineOnlyKey] = offlineOnly }
    }

    companion object {
        fun create(context: Context): AppPreferencesRepository =
            AppPreferencesRepository(context.appPreferencesDataStore)
    }
}
