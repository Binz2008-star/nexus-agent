package com.nexus.agent.data.network

import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * One primary call, then at most ONE free OpenRouter call.
 * No automatic retries of a failed fallback. Failover is opt-in, and only
 * enabled for known primary-provider availability/credit failures.
 *
 * For streaming, never switch providers after exposing any partial answer.
 */
class FailoverChatCompletionClient(
    private val primary: ChatCompletionClient,
    private val freeFallback: ChatCompletionClient,
    private val enabled: suspend () -> Boolean
) : ChatCompletionClient {

    override suspend fun complete(
        history: List<ChatMessage>,
        model: String
    ): Result<String> {
        val first = primary.complete(history, model)
        if (!canFailover(first.exceptionOrNull())) return first
        if (!enabled()) return first
        return freeFallback.complete(history, OpenRouterClient.FREE_MODEL)
    }

    override suspend fun completeWithTools(
        history: List<ChatMessage>,
        model: String,
        tools: List<JsonObject>
    ): Result<CompletionResult> {
        val first = primary.completeWithTools(history, model, tools)
        if (!canFailover(first.exceptionOrNull())) return first
        if (!enabled()) return first
        return freeFallback.completeWithTools(history, OpenRouterClient.FREE_MODEL, tools)
    }

    override suspend fun completeStream(
        history: List<ChatMessage>,
        model: String,
        onDelta: suspend (String) -> Unit
    ): Result<String> {
        var emitted = false
        val first = primary.completeStream(history, model) { delta ->
            if (delta.isNotEmpty()) emitted = true
            onDelta(delta)
        }
        // A partially emitted completion must NEVER be replayed to another provider.
        if (emitted || !canFailover(first.exceptionOrNull())) return first
        if (!enabled()) return first
        return freeFallback.completeStream(history, OpenRouterClient.FREE_MODEL, onDelta)
    }

    private fun canFailover(error: Throwable?): Boolean {
        if (error == null || error is CancellationException) return false
        if (error is IllegalStateException &&
            error.message == "No DeepSeek API key configured") return true
        if (error !is IOException) return false
        return error.message in setOf(
            "DeepSeek API error 402",
            "DeepSeek API error 429",
            "DeepSeek API error 500",
            "DeepSeek API error 502",
            "DeepSeek API error 503",
            "DeepSeek API error 504"
        )
    }
}
