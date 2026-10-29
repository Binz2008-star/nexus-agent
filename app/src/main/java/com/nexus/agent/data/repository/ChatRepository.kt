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
        text: String,
        model: String,
        onDelta: suspend (String) -> Unit
    ): SendResult = withContext(Dispatchers.IO) {
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
        val fullHistory = buildHistoryWithSystem(history)

        try {
            val result = client.completeStream(fullHistory, model, onDelta)
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
     * Streams a completion against existing history (no new user insert). Used for retry.
     */
    suspend fun retryCompletionStream(
        model: String,
        onDelta: suspend (String) -> Unit
    ): SendResult = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key == null) {
            return@withContext SendResult.Failure("no_key")
        }
        val history = messageDao.getAllChronologicalOnce().map { it.toDomain() }
        if (history.isEmpty() || history.last().role != ChatMessage.Role.USER) {
            return@withContext SendResult.Failure("nothing_to_retry")
        }
        val fullHistory = buildHistoryWithSystem(history)
        try {
            val result = client.completeStream(fullHistory, model, onDelta)
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

    // Keep non-streaming versions for compatibility / tests
    suspend fun sendMessage(text: String, model: String): SendResult =
        sendMessageStream(text, model) { /* ignore deltas */ }

    suspend fun retryCompletion(model: String): SendResult =
        retryCompletionStream(model) { /* ignore deltas */ }

    private suspend fun buildHistoryWithSystem(history: List<ChatMessage>): List<ChatMessage> {
        val prompt = systemPromptProvider().trim()
        return if (prompt.isEmpty()) {
            history
        } else {
            listOf(ChatMessage(ChatMessage.Role.SYSTEM, prompt)) + history
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


