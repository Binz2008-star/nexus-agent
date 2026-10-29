package com.nexus.agent.data.repository

import com.nexus.agent.data.local.ConversationDao
import com.nexus.agent.data.local.ConversationEntity
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
    private val conversationDao: ConversationDao,
    private val client: ChatCompletionClient,
    private val apiKeyProvider: suspend () -> String?,
    private val systemPromptProvider: suspend () -> String = { "" }
) {
    fun observeConversations(): Flow<List<ConversationEntity>> = conversationDao.getAll()

    fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> =
        messageDao.getByConversation(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }

    suspend fun createConversation(title: String = "New chat"): Long = withContext(Dispatchers.IO) {
        conversationDao.insert(ConversationEntity(title = title))
    }

    /**
     * Persists the user message, then streams a completion.
     * onDelta is called with each text fragment.
     * The assistant message is persisted only after the full stream succeeds.
     */
    suspend fun sendMessageStream(
        conversationId: Long,
        text: String,
        model: String,
        onDelta: suspend (String) -> Unit
    ): SendResult = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext SendResult.Failure("empty")
        if (apiKeyProvider() == null) return@withContext SendResult.Failure("no_key")

        val userEntity = MessageEntity.fromDomain(
            ChatMessage(ChatMessage.Role.USER, trimmed),
            conversationId
        )
        messageDao.insert(userEntity)

        val existing = messageDao.getByConversationOnce(conversationId)
        if (existing.size == 1) {
            val shortTitle = trimmed.take(36).let { if (trimmed.length > 36) "$it…" else it }
            conversationDao.updateTitleAndTimestamp(conversationId, shortTitle, System.currentTimeMillis())
        } else {
            conversationDao.updateTitleAndTimestamp(
                conversationId,
                existing.firstOrNull()?.text?.take(36) ?: "Chat",
                System.currentTimeMillis()
            )
        }

        val history = messageDao.getByConversationOnce(conversationId).map { it.toDomain() }
        val fullHistory = buildHistoryWithSystem(history)

        try {
            val result = client.completeStream(fullHistory, model, onDelta)
            result.fold(
                onSuccess = { reply ->
                    messageDao.insert(
                        MessageEntity.fromDomain(
                            ChatMessage(ChatMessage.Role.ASSISTANT, reply),
                            conversationId
                        )
                    )
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

    suspend fun retryCompletionStream(
        conversationId: Long,
        model: String,
        onDelta: suspend (String) -> Unit
    ): SendResult = withContext(Dispatchers.IO) {
        if (apiKeyProvider() == null) return@withContext SendResult.Failure("no_key")
        val history = messageDao.getByConversationOnce(conversationId).map { it.toDomain() }
        if (history.isEmpty() || history.last().role != ChatMessage.Role.USER) {
            return@withContext SendResult.Failure("nothing_to_retry")
        }
        val fullHistory = buildHistoryWithSystem(history)
        try {
            val result = client.completeStream(fullHistory, model, onDelta)
            result.fold(
                onSuccess = { reply ->
                    messageDao.insert(
                        MessageEntity.fromDomain(
                            ChatMessage(ChatMessage.Role.ASSISTANT, reply),
                            conversationId
                        )
                    )
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

    // Compatibility wrappers (used by older tests)
    suspend fun sendMessage(text: String, model: String): SendResult =
        sendMessageStream(0L, text, model) { }
    suspend fun retryCompletion(model: String): SendResult =
        retryCompletionStream(0L, model) { }

    private suspend fun buildHistoryWithSystem(history: List<ChatMessage>): List<ChatMessage> {
        val prompt = systemPromptProvider().trim()
        return if (prompt.isEmpty()) {
            history
        } else {
            listOf(ChatMessage(ChatMessage.Role.SYSTEM, prompt)) + history
        }
    }

    private fun safeErrorCode(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("401") -> "auth"
            msg.contains("402") -> "quota"
            msg.contains("429") -> "rate"
            msg.contains("No DeepSeek API key") -> "no_key"
            msg.contains("Empty completion") || msg.contains("Empty body") -> "empty_response"
            msg.contains("timeout", ignoreCase = true) || msg.contains("timed out", ignoreCase = true) -> "timeout"
            msg.contains("Unable to resolve host") || msg.contains("Failed to connect") || msg.contains("Network") -> "network"
            msg.contains("DeepSeek API error") -> "api_${msg.filter { it.isDigit() }.take(3)}"
            else -> "generic:${e.javaClass.simpleName}"
        }
    }
}


