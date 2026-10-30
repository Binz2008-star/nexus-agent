package com.nexus.agent.data.network

import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        model: String
    ): Result<String> = completeInternal(history, model, stream = false, onDelta = null)

    override suspend fun completeStream(
        history: List<ChatMessage>,
        model: String,
        onDelta: suspend (String) -> Unit
    ): Result<String> = completeInternal(history, model, stream = true, onDelta = onDelta)

    private suspend fun completeInternal(
        history: List<ChatMessage>,
        model: String,
        stream: Boolean,
        onDelta: (suspend (String) -> Unit)?
    ): Result<String> {
        val key = apiKeyProvider()
            ?: return Result.failure(IllegalStateException("No DeepSeek API key configured"))

        val messages = history.map {
            ApiMessage(
                role = when (it.role) {
                    ChatMessage.Role.USER -> "user"
                    ChatMessage.Role.ASSISTANT -> "assistant"
                    ChatMessage.Role.SYSTEM -> "system"
                },
                content = it.text
            )
        }

        val requestBody = ChatCompletionRequest(
            model = model,
            messages = messages,
            thinking = ThinkingConfig(type = "disabled"),
            stream = stream
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
                if (!response.isSuccessful) {
                    return Result.failure(IOException("DeepSeek API error ${response.code}"))
                }
                val body = response.body ?: return Result.failure(IOException("Empty body"))
                if (!stream) {
                    val text = body.string()
                    val parsed = json.decodeFromString<ChatCompletionResponse>(text)
                    val content = parsed.choices.firstOrNull()?.message?.content
                        ?: return Result.failure(IOException("Empty completion from DeepSeek"))
                    return Result.success(content)
                }

                // Streaming (SSE)
                val full = StringBuilder()
                body.source().use { source ->
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank() || !line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        try {
                            val chunk = json.decodeFromString<ChatCompletionChunk>(data)
                            val delta = chunk.choices.firstOrNull()?.delta?.content
                            if (!delta.isNullOrEmpty()) {
                                full.append(delta)
                                onDelta?.invoke(delta)
                            }
                        } catch (_: Exception) {
                            // ignore malformed chunk
                        }
                    }
                }
                if (full.isEmpty()) {
                    Result.failure(IOException("Empty completion from DeepSeek"))
                } else {
                    Result.success(full.toString())
                }
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
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
