package com.nexus.agent.data.repository

import com.nexus.agent.data.local.ConversationDao
import com.nexus.agent.data.local.ConversationEntity
import com.nexus.agent.data.local.MessageDao
import com.nexus.agent.data.local.MessageEntity
import com.nexus.agent.data.network.ChatCompletionClient
import com.nexus.agent.data.network.CompletionResult
import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.domain.router.AiModelRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

sealed class SendResult {
    data object Success : SendResult()
    data class Failure(val errorCode: String) : SendResult()
}

class ChatRepository(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val client: ChatCompletionClient,
    private val apiKeyProvider: suspend () -> String?,
    private val systemPromptProvider: suspend () -> String = { "" },
    private val router: AiModelRouter? = null,
    private val personaProvider: suspend () -> String? = { null },
    private val mirror: ConversationMirror? = null
) {
    // Fire-and-forget scope for the best-effort Neon mirror, so a slow/hung mirror can never
    // delay the chat flow (Room persists first; the mirror is an async side effect).
    private val mirrorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        context: String? = null,
        onDelta: suspend (String) -> Unit
    ): SendResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SendResult.Failure("empty")
        // Decrypt the key off the caller's thread — Keystore access is blocking I/O.
        if (withContext(Dispatchers.IO) { apiKeyProvider() } == null) {
            return SendResult.Failure("no_key")
        }
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
            mirrorMessage(conversationId, ROLE_USER, trimmed)
        }
    }

    /** Best-effort, fire-and-forget mirror to Neon (never throws into the caller). */
    private fun mirrorMessage(conversationId: Long, role: String, content: String) {
        val target = mirror ?: return
        mirrorScope.launch {
            runCatching { target.mirror(conversationId.toString(), role, content) }
        }
    }

    /**
     * Persists a local assistant reply (e.g. a safety refusal or a cognitive-load warning)
     * without touching the network. Used by the ViewModel to surface local responses as normal
     * chat messages.
     */
    suspend fun persistAssistantMessage(conversationId: Long, text: String) {
        withContext(Dispatchers.IO) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@withContext
            messageDao.insert(
                MessageEntity.fromDomain(
                    ChatMessage(ChatMessage.Role.ASSISTANT, trimmed),
                    conversationId
                )
            )
        }
    }

    /**
     * Builds the full message list for a completion (system prompts + ordered conversation
     * history), exposed for the model-driven tool loop. Runs on [Dispatchers.IO].
     */
    suspend fun buildHistory(conversationId: Long, context: String? = null): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val history = messageDao.getByConversationOnce(conversationId).map { it.toDomain() }
            buildHistoryWithSystem(history, context)
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
            val result = streamCompletion(fullHistory, model, onDelta)
            result.fold(
                onSuccess = { reply ->
                    messageDao.insert(
                        MessageEntity.fromDomain(
                            ChatMessage(ChatMessage.Role.ASSISTANT, reply),
                            conversationId
                        )
                    )
                    mirrorMessage(conversationId, ROLE_ASSISTANT, reply)
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
        context: String? = null,
        onDelta: suspend (String) -> Unit
    ): SendResult = withContext(Dispatchers.IO) {
        if (apiKeyProvider() == null) return@withContext SendResult.Failure("no_key")
        val history = messageDao.getByConversationOnce(conversationId).map { it.toDomain() }
        if (history.isEmpty() || history.last().role != ChatMessage.Role.USER) {
            return@withContext SendResult.Failure("nothing_to_retry")
        }
        val fullHistory = buildHistoryWithSystem(history, context)
        try {
            val result = streamCompletion(fullHistory, model, onDelta)
            result.fold(
                onSuccess = { reply ->
                    messageDao.insert(
                        MessageEntity.fromDomain(
                            ChatMessage(ChatMessage.Role.ASSISTANT, reply),
                            conversationId
                        )
                    )
                    mirrorMessage(conversationId, ROLE_ASSISTANT, reply)
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

    /**
     * Sends the completion through the [AiModelRouter] when one is wired in, otherwise falls
     * back to the raw [client] (keeps older tests and callers working). The router resolves the
     * requested model to the right provider; both paths run on [Dispatchers.IO].
     */
    private suspend fun streamCompletion(
        history: List<ChatMessage>,
        model: String,
        onDelta: suspend (String) -> Unit
    ): Result<String> =
        if (router != null) router.routeStream(history, model, onDelta)
        else client.completeStream(history, model, onDelta)

    /**
     * Tool-aware (function-calling) completion. Runs on [Dispatchers.IO]. The model either
     * answers directly or requests tool calls; the caller (ToolLoop) validates + executes.
     */
    suspend fun completeWithTools(
        history: List<ChatMessage>,
        model: String,
        tools: List<JsonObject>
    ): Result<CompletionResult> = withContext(Dispatchers.IO) {
        client.completeWithTools(history, model, tools)
    }

    private suspend fun buildHistoryWithSystem(
        history: List<ChatMessage>,
        context: String? = null
    ): List<ChatMessage> {
        val customPrompt = systemPromptProvider().trim()
        val persona = personaProvider()?.trim()
        val systemMessages = buildList {
            add(ChatMessage(ChatMessage.Role.SYSTEM, NAME_SAFETY_PROMPT))
            add(ChatMessage(ChatMessage.Role.SYSTEM, IDENTITY_PROMPT))
            if (customPrompt.isNotEmpty()) {
                add(ChatMessage(ChatMessage.Role.SYSTEM, customPrompt))
            }
            if (!persona.isNullOrBlank()) {
                add(ChatMessage(ChatMessage.Role.SYSTEM, persona))
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
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

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

        /**
         * NEXUS identity + capabilities. Injected on every request so the model answers as
         * NEXUS Agent (a local assistant with specific tools) instead of a generic assistant.
         */
        const val IDENTITY_PROMPT =
            "أنت NEXUS Agent — مساعد ذكي محلي يعمل على جهاز المستخدم. يجب أن تعرّف نفسك دائمًا بهذا " +
                "الاسم، ولا تدّعِ أبدًا أنك مساعد عام أو مجرّد روبوت محادثة.\n" +
                "قدراتك:\n" +
                "- البحث في الويب للإجابة بمعلومات حديثة مع ذكر المصادر.\n" +
                "- البحث في قاعدة المعرفة الخاصة بالمستخدم (Second Brain).\n" +
                "- تشغيل مهام في الخلفية والرد بنتائجها.\n" +
                "- توليد شعر عاطفي عند الطلب.\n" +
                "- توجيه تمارين التنفس والاسترخاء.\n" +
                "- التحدث باللهجات العربية المختلفة وبأسلوب ودود ومتعاطف.\n" +
                "عندما يسألك المستخدم «من أنت» أو «ما هي خدماتك» أو «ماذا تستطيع أن تفعل»، صف نفسك " +
                "كـ NEXUS Agent واذكر هذه القدرات مباشرةً — لا تجب كمساعد عام.\n\n" +
                "You are NEXUS Agent, a local intelligent assistant running on the user's device. " +
                "Always identify yourself as NEXUS Agent; never describe yourself as a generic chatbot.\n" +
                "Your capabilities: search the web with cited sources, search the user's Second Brain " +
                "knowledge base, run background tasks, generate emotional poetry, guide breathing and " +
                "relaxation exercises, and speak various Arabic dialects in a friendly, empathetic tone.\n" +
                "When asked who you are or what your services are, answer as NEXUS Agent and list these " +
                "capabilities directly — never answer as a generic assistant."
    }
}


