package com.nexus.agent.data.network

import com.nexus.agent.domain.model.ChatMessage
import kotlinx.serialization.json.JsonObject

/**
 * Result of a tool-aware completion: either a plain text answer, or a request to run tools
 * (which the orchestrator must validate + execute before continuing the loop).
 */
sealed interface CompletionResult {
    data class Answer(val content: String) : CompletionResult
    data class ToolCallRequested(val toolCalls: List<ToolCallDto>) : CompletionResult
}

/**
 * Abstraction over chat completion so the repository can be tested without a real HTTP client.
 */
interface ChatCompletionClient {
    suspend fun complete(history: List<ChatMessage>, model: String): Result<String>

    /**
     * Streaming completion. Calls onDelta with each text fragment.
     * Returns the full accumulated text on success.
     */
    suspend fun completeStream(
        history: List<ChatMessage>,
        model: String,
        onDelta: suspend (String) -> Unit
    ): Result<String>

    /**
     * Tool-aware (function-calling) completion. The model either answers directly or requests
     * one or more tool calls ([CompletionResult.ToolCallRequested]).
     */
    suspend fun completeWithTools(
        history: List<ChatMessage>,
        model: String,
        tools: List<JsonObject>
    ): Result<CompletionResult>
}
