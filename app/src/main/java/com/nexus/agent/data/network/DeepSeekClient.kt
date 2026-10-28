package com.nexus.agent.data.network

import com.nexus.agent.domain.model.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin OkHttp client for DeepSeek Chat Completions (OpenAI-compatible).
 *
 * Base URL: https://api.deepseek.com
 * Endpoint: POST /chat/completions
 * Auth: Authorization: Bearer <apiKey>
 *
 * Supported models: deepseek-flash, deepseek-v4-pro
 */
class DeepSeekClient(
    private val apiKeyProvider: suspend () -> String?,
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ChatCompletionClient {

    override suspend fun complete(
        history: List<ChatMessage>,
        model: String = DEFAULT_MODEL
    ): Result<String> {
        val key = apiKeyProvider()
            ?: return Result.failure(IllegalStateException("No DeepSeek API key configured"))

        val messages = history.map {
            ApiMessage(
                role = when (it.role) {
                    ChatMessage.Role.USER -> "user"
                    ChatMessage.Role.ASSISTANT -> "assistant"
                },
                content = it.text
            )
        }

        val requestBody = ChatCompletionRequest(
            model = model,
            messages = messages,
            thinking = ThinkingConfig(type = "disabled") // prefer fast replies for chat UI
        )

        val bodyJson = json.encodeToString(requestBody)
        val request = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Result.failure(
                        IOException("DeepSeek API error ${response.code}: ${body.take(200)}")
                    )
                }
                val parsed = json.decodeFromString<ChatCompletionResponse>(body)
                val content = parsed.choices.firstOrNull()?.message?.content
                    ?: return Result.failure(IOException("Empty completion from DeepSeek"))
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-flash"
        const val PRO_MODEL = "deepseek-v4-pro"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
