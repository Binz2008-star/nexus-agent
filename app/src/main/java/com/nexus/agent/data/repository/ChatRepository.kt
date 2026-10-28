package com.nexus.agent.data.repository

import com.nexus.agent.data.local.MessageDao
import com.nexus.agent.data.local.MessageEntity
import com.nexus.agent.data.network.ChatCompletionClient
import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

sealed class SendResult {
    data object Success : SendResult()
    data class Failure(val errorCode: String) : SendResult()
}

class ChatRepository(
    private val messageDao: MessageDao,
    private val client: ChatCompletionClient,
    private val apiKeyProvider: suspend () -> String?
) {
    fun observeMessages(): Flow<List<ChatMessage>> =
        messageDao.getAllChronological().map { entities ->
            entities.map { it.toDomain() }
        }

    /**
     * Persists the user message, then requests a completion. On success, persists the assistant reply.
     * On failure, the user message remains; no assistant message is written.
     * Does not claim the HTTP request survives process death.
     */
    suspend fun sendMessage(text: String, model: String): SendResult = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return@withContext SendResult.Failure("empty")
        }

        val key = apiKeyProvider()
        if (key == null) {
            return@withContext SendResult.Failure("no_key")
        }

        val userMessage = ChatMessage(ChatMessage.Role.USER, trimmed)
        val userEntity = MessageEntity.fromDomain(userMessage)
        messageDao.insert(userEntity)

        val history = messageDao.getAllChronologicalOnce().map { it.toDomain() }

        try {
            val result = client.complete(history, model)
            result.fold(
                onSuccess = { reply ->
                    val assistant = ChatMessage(ChatMessage.Role.ASSISTANT, reply)
                    messageDao.insert(MessageEntity.fromDomain(assistant))
                    SendResult.Success
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    SendResult.Failure(safeErrorCode(e))
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.Failure(safeErrorCode(e))
        }
    }

    /**
     * Attempts completion against the current persisted history (no new user message inserted).
     * Used for retry after a failure so the user prompt is not duplicated.
     */
    suspend fun retryCompletion(model: String): SendResult = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key == null) {
            return@withContext SendResult.Failure("no_key")
        }
        val history = messageDao.getAllChronologicalOnce().map { it.toDomain() }
        if (history.isEmpty() || history.last().role != ChatMessage.Role.USER) {
            return@withContext SendResult.Failure("nothing_to_retry")
        }
        try {
            val result = client.complete(history, model)
            result.fold(
                onSuccess = { reply ->
                    val assistant = ChatMessage(ChatMessage.Role.ASSISTANT, reply)
                    messageDao.insert(MessageEntity.fromDomain(assistant))
                    SendResult.Success
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    SendResult.Failure(safeErrorCode(e))
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.Failure(safeErrorCode(e))
        }
    }

    private fun safeErrorCode(e: Throwable): String {
        // Return stable codes only — no raw bodies or keys
        return when {
            e.message?.contains("401") == true -> "auth"
            e.message?.contains("402") == true -> "quota"
            e.message?.contains("429") == true -> "rate"
            e.message?.contains("No DeepSeek API key") == true -> "no_key"
            else -> "generic"
        }
    }
}


