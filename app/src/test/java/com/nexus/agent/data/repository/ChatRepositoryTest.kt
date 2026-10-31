package com.nexus.agent.data.repository

import com.nexus.agent.data.local.ConversationDao
import com.nexus.agent.data.local.ConversationEntity
import com.nexus.agent.data.local.MessageDao
import com.nexus.agent.data.local.MessageEntity
import com.nexus.agent.data.network.ChatCompletionClient
import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTest {
    private class FakeDao : MessageDao {
        private val store = mutableListOf<MessageEntity>()
        private val flow = MutableStateFlow<List<MessageEntity>>(emptyList())
        override suspend fun insert(message: MessageEntity): Long {
            val withId = message.copy(id = (store.size + 1).toLong())
            store.add(withId)
            flow.value = store.toList()
            return withId.id
        }
        override fun getByConversation(conversationId: Long): Flow<List<MessageEntity>> = flow.asStateFlow()
        override suspend fun getByConversationOnce(conversationId: Long): List<MessageEntity> = store.toList()
    }

    private class FakeConversationDao : ConversationDao {
        override suspend fun insert(conversation: ConversationEntity): Long = 1L
        override fun getAll(): Flow<List<ConversationEntity>> = flowOf(emptyList())
        override suspend fun updateTitleAndTimestamp(id: Long, title: String, ts: Long) {}
        override suspend fun delete(id: Long) {}
    }

    private class FakeClient(private val replies: List<Result<String>>) : ChatCompletionClient {
        var calls = 0
        var lastHistory: List<ChatMessage> = emptyList()
        override suspend fun complete(history: List<ChatMessage>, model: String): Result<String> {
            val r = replies.getOrElse(calls) { Result.failure(Exception("no more")) }
            calls++
            return r
        }
        override suspend fun completeStream(
            history: List<ChatMessage>,
            model: String,
            onDelta: suspend (String) -> Unit
        ): Result<String> {
            lastHistory = history
            val r = complete(history, model)
            if (r.isSuccess) onDelta(r.getOrThrow())
            return r
        }
        override suspend fun completeWithTools(
            history: List<ChatMessage>,
            model: String,
            tools: List<kotlinx.serialization.json.JsonObject>
        ): Result<com.nexus.agent.data.network.CompletionResult> {
            val r = complete(history, model)
            return if (r.isSuccess) Result.success(com.nexus.agent.data.network.CompletionResult.Answer(r.getOrThrow()))
            else Result.failure(r.exceptionOrNull() ?: Exception("no more"))
        }
    }

    private fun repo(dao: FakeDao, client: FakeClient) =
        ChatRepository(dao, FakeConversationDao(), client, apiKeyProvider = { "key" })

    @Test
    fun sendMessage_success_persistsUserAndAssistant() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(listOf(Result.success("reply")))
        val r = repo(dao, client)
        val result = r.sendMessageStream(1L, "hello", "deepseek-flash") { }
        assertTrue(result is SendResult.Success)
        val msgs = dao.getByConversationOnce(1L)
        assertEquals(2, msgs.size)
        assertEquals("USER", msgs[0].role)
        assertEquals("hello", msgs[0].text)
        assertEquals("ASSISTANT", msgs[1].role)
        assertEquals("reply", msgs[1].text)
    }

    @Test
    fun sendMessage_failure_persistsOnlyUser() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(listOf(Result.failure(Exception("network"))))
        val r = repo(dao, client)
        val result = r.sendMessageStream(1L, "hello", "deepseek-flash") { }
        assertTrue(result is SendResult.Failure)
        val msgs = dao.getByConversationOnce(1L)
        assertEquals(1, msgs.size)
        assertEquals("USER", msgs[0].role)
    }

    @Test
    fun sendMessage_blank_doesNotInsert() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(emptyList())
        val r = repo(dao, client)
        val result = r.sendMessageStream(1L, "   ", "deepseek-flash") { }
        assertTrue(result is SendResult.Failure)
        assertEquals(0, dao.getByConversationOnce(1L).size)
    }

    @Test
    fun nameSafetyPromptIsAlwaysInjectedFirst() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(listOf(Result.success("ok")))
        val r = repo(dao, client)
        r.sendMessageStream(1L, "what's my name?", "deepseek-flash") { }
        val history = client.lastHistory
        assertTrue(history.isNotEmpty())
        assertEquals(ChatMessage.Role.SYSTEM, history.first().role)
        assertTrue(history.first().text.contains("Do not assume"))
        assertTrue(history.first().text.contains("لا تفترض"))
    }

    @Test
    fun identityPromptIsInjectedAfterNameSafety() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(listOf(Result.success("ok")))
        val r = repo(dao, client)
        r.sendMessageStream(1L, "hi", "deepseek-flash") { }
        val history = client.lastHistory
        assertTrue(history.size >= 2)
        assertEquals(ChatMessage.Role.SYSTEM, history[1].role)
        assertTrue(history[1].text.contains("NEXUS Agent"))
    }

    @Test
    fun customPromptIsIncludedAfterNameSafety() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(listOf(Result.success("ok")))
        val r = ChatRepository(dao, FakeConversationDao(), client, { "key" }, { "custom prompt" })
        r.sendMessageStream(1L, "hi", "deepseek-flash") { }
        val history = client.lastHistory
        assertTrue(history.size >= 3)
        assertEquals(ChatMessage.Role.SYSTEM, history[0].role)
        assertTrue(history[0].text.contains("Do not assume"))
        assertEquals(ChatMessage.Role.SYSTEM, history[2].role)
        assertEquals("custom prompt", history[2].text)
    }

    @Test
    fun contextIsInjectedAfterCustomPromptBeforeUserMessage() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(listOf(Result.success("ok")))
        val r = ChatRepository(dao, FakeConversationDao(), client, { "key" }, { "custom prompt" })
        r.sendMessageStream(1L, "hi", "deepseek-flash", "grounding-excerpts") { }
        val history = client.lastHistory
        assertEquals(ChatMessage.Role.SYSTEM, history[0].role)
        assertTrue(history[0].text.contains("Do not assume"))
        assertEquals(ChatMessage.Role.SYSTEM, history[1].role)
        assertTrue(history[1].text.contains("NEXUS Agent"))
        assertEquals(ChatMessage.Role.SYSTEM, history[2].role)
        assertEquals("custom prompt", history[2].text)
        assertEquals(ChatMessage.Role.SYSTEM, history[3].role)
        assertEquals("grounding-excerpts", history[3].text)
        assertEquals(ChatMessage.Role.USER, history[4].role)
        assertEquals("hi", history[4].text)
    }

    @Test
    fun personaInstructionIsInjectedAfterCustomPrompt() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(listOf(Result.success("ok")))
        val r = ChatRepository(
            dao, FakeConversationDao(), client, { "key" }, { "custom prompt" },
            personaProvider = { "respond in Gulf Arabic with a friendly tone" }
        )
        r.sendMessageStream(1L, "hi", "deepseek-flash") { }
        val history = client.lastHistory
        assertEquals(ChatMessage.Role.SYSTEM, history[0].role)
        assertTrue(history[0].text.contains("Do not assume"))
        assertEquals("custom prompt", history[2].text)
        assertEquals("respond in Gulf Arabic with a friendly tone", history[3].text)
        assertEquals(ChatMessage.Role.USER, history[4].role)
    }
}
