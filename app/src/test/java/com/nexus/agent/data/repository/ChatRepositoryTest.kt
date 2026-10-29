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
            val r = complete(history, model)
            if (r.isSuccess) onDelta(r.getOrThrow())
            return r
        }
    }

    private fun repo(dao: FakeDao, client: FakeClient) =
        ChatRepository(dao, FakeConversationDao(), client) { "key" }

    @Test
    fun sendMessage_success_persistsUserAndAssistant() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(listOf(Result.success("reply")))
        val r = repo(dao, client)
        val result = r.sendMessage("hello", "deepseek-flash")
        assertTrue(result is SendResult.Success)
        val msgs = dao.getByConversationOnce(0L)
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
        val result = r.sendMessage("hello", "deepseek-flash")
        assertTrue(result is SendResult.Failure)
        val msgs = dao.getByConversationOnce(0L)
        assertEquals(1, msgs.size)
        assertEquals("USER", msgs[0].role)
    }

    @Test
    fun sendMessage_blank_doesNotInsert() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(emptyList())
        val r = repo(dao, client)
        val result = r.sendMessage("   ", "deepseek-flash")
        assertTrue(result is SendResult.Failure)
        assertEquals(0, dao.getByConversationOnce(0L).size)
    }

    @Test
    fun retryCompletion_doesNotDuplicateUser() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(listOf(Result.failure(Exception("fail")), Result.success("ok")))
        val r = repo(dao, client)
        r.sendMessage("hello", "m")
        assertEquals(1, dao.getByConversationOnce(0L).size)
        val result = r.retryCompletion("m")
        assertTrue(result is SendResult.Success)
        val msgs = dao.getByConversationOnce(0L)
        assertEquals(2, msgs.size)
        assertEquals(1, msgs.count { it.role == "USER" })
    }
}
