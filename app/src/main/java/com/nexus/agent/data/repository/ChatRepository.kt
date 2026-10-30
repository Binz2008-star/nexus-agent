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
        onDelta: suspend (String) -> Unit,
        context: String? = null
    ): SendResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SendResult.Failure("empty")
        if (apiKeyProvider() == null) return SendResult.Failure("no_key")
        persistUserMessage(conversationId, trimmed)
        return completeWithContext(conversationId, model, context, onDelta)
    }

    /**
     * Persists the user message immediately so it appears in the UI before any slow tool
     * step runs. Used by the agentic flow, which separates "persist user message" from
     * "run tool, then stream an answer grounded in the excerpts".
     */
    suspend fun persistUserMessage(conversationId: Long, text: String) {
        withContext(Dispatchers.IO) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@withContext

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
        }
    }

    /**
     * Streams a completion against the existing conversation history (which must already
     * include the user message), optionally grounding the answer in [context] (tool excerpts).
     */
    suspend fun completeWithContext(
        conversationId: Long,
        model: String,
        context: String?,
        onDelta: suspend (String) -> Unit
    ): SendResult = withContext(Dispatchers.IO) {
        if (apiKeyProvider() == null) return@withContext SendResult.Failure("no_key")

        val history = messageDao.getByConversationOnce(conversationId).map { it.toDomain() }
        val fullHistory = buildHistoryWithSystem(history, context)

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

    private suspend fun buildHistoryWithSystem(
        history: List<ChatMessage>,
        context: String? = null
    ): List<ChatMessage> {
        val customPrompt = systemPromptProvider().trim()
        val systemMessages = buildList {
            add(ChatMessage(ChatMessage.Role.SYSTEM, NAME_SAFETY_PROMPT))
            if (customPrompt.isNotEmpty()) {
                add(ChatMessage(ChatMessage.Role.SYSTEM, customPrompt))
            }
            if (!context.isNullOrBlank()) {
                add(ChatMessage(ChatMessage.Role.SYSTEM, context))
            }
        }
        return systemMessages + history
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

    companion object {
        /**
         * Mandatory system instruction injected on every request. Prevents the model from
         * hallucinating or reusing a user name it was never explicitly given in the current
         * conversation.
         */
        const val NAME_SAFETY_PROMPT =
            "CRITICAL: Do not assume the user's name, gender, or location. If you do not " +
                "know the user's name, do not use any name. If the user corrects you, " +
                "immediately apologize, forget the old name, and continue without that name " +
                "unless the user gives you a new one.\n\n" +
                "حرج: لا تفترض اسم المستخدم أو جنسه أو موقعه. إذا كنت لا تعرف اسم المستخدم، لا " +
                "تستخدم أي اسم. إذا صحّحك المستخدم، اعتذر فورًا، وانسَ الاسم القديم، وتابع " +
                "دون هذا الاسم إلا إذا أعطاك المستخدم اسمًا جديدًا."
    }
}


