package com.nexus.agent.data.network

import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Free-tier-only OpenRouter chat client.
 *
 * A user-provided key is decrypted at request time. No credential or transcript
 * is logged, persisted by this client, or embedded in a build.
 * A fixed model ID prevents silently charging for paid models.
 */
class OpenRouterClient(
    private val apiKeyProvider: suspend () -> String?,
    private val httpClient: OkHttpClient = defaultClient(),
    private val baseUrl: String = BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ChatCompletionClient {

    override suspend fun complete(
        history: List<ChatMessage>, model: String
    ): Result<String> = completeInternal(history, stream = false, onDelta = null)

    override suspend fun completeStream(
        history: List<ChatMessage>,
        model: String,
        onDelta: suspend (String) -> Unit
    ): Result<String> = completeInternal(history, stream = true, onDelta = onDelta)

    override suspend fun completeWithTools(
        history: List<ChatMessage>,
        model: String,
        tools: List<JsonObject>
    ): Result<CompletionResult> {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("No OpenRouter API key configured"))
        return try {
            val payload = ChatCompletionRequest(
                model = FREE_MODEL,
                messages = history.toApiMessages(),
                tools = tools.takeIf { it.isNotEmpty() },
                toolChoice = if (tools.isNotEmpty()) "auto" else null
            )
            request(key, payload).use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("OpenRouter API error " + response.code))
                }
                val raw = response.body?.string()
                    ?: return Result.failure(IOException("Empty OpenRouter response"))
                val parsed = json.decodeFromString<ChatCompletionResponse>(raw)
                val message = parsed.choices.firstOrNull()?.message
                    ?: return Result.failure(IOException("Empty OpenRouter completion"))
                val calls = message.toolCalls
                if (!calls.isNullOrEmpty()) {
                    Result.success(CompletionResult.ToolCallRequested(calls))
                } else {
                    val content = message.content?.takeIf { it.isNotBlank() }
                        ?: return Result.failure(IOException("Empty OpenRouter completion"))
                    Result.success(CompletionResult.Answer(content))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun completeInternal(
        history: List<ChatMessage>,
        stream: Boolean,
        onDelta: (suspend (String) -> Unit)?
    ): Result<String> {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("No OpenRouter API key configured"))
        val payload = ChatCompletionRequest(
            model = FREE_MODEL,
            messages = history.toApiMessages(),
            stream = stream
        )
        return try {
            request(key, payload).use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("OpenRouter API error " + response.code))
                }
                val body = response.body
                    ?: return Result.failure(IOException("Empty OpenRouter response"))
                if (!stream) {
                    val parsed = json.decodeFromString<ChatCompletionResponse>(body.string())
                    val content = parsed.choices.firstOrNull()?.message?.content
                        ?.takeIf { it.isNotBlank() }
                        ?: return Result.failure(IOException("Empty OpenRouter completion"))
                    return Result.success(content)
                }
                val text = StringBuilder()
                body.source().use { source ->
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        try {
                            val chunk = json.decodeFromString<ChatCompletionChunk>(data)
                            val delta = chunk.choices.firstOrNull()?.delta?.content
                            if (!delta.isNullOrEmpty()) {
                                text.append(delta)
                                onDelta?.invoke(delta)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Ignore malformed SSE framing; never interpret it as instructions.
                        }
                    }
                }
                if (text.isEmpty()) Result.failure(IOException("Empty OpenRouter completion"))
                else Result.success(text.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun List<ChatMessage>.toApiMessages(): List<ApiMessage> = map { item ->
        ApiMessage(
            role = when (item.role) {
                ChatMessage.Role.SYSTEM -> "system"
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
            },
            content = item.text
        )
    }

    private fun request(key: String, body: ChatCompletionRequest) =
        httpClient.newCall(
            Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).execute()

    companion object {
        const val BASE_URL = "https://openrouter.ai/api/v1"
        const val FREE_MODEL = "openrouter/free"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient() = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
