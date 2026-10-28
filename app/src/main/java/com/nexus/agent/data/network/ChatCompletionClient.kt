package com.nexus.agent.data.network

import com.nexus.agent.domain.model.ChatMessage

/**
 * Abstraction over chat completion so the repository can be tested without a real HTTP client.
 */
interface ChatCompletionClient {
    suspend fun complete(history: List<ChatMessage>, model: String): Result<String>
}
