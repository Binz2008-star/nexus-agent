package com.nexus.agent.data.repository

import com.nexus.agent.data.local.MessageDao
import com.nexus.agent.data.local.MessageEntity
import com.nexus.agent.data.network.ChatCompletionClient
import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        override fun getAllChronological(): Flow<List<MessageEntity>> = flow.asStateFlow()
        override suspend fun getAllChronologicalOnce(): List<MessageEntity> = store.toList()
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

    @Test
    fun sendMessage_success_persistsUserAndAssistant() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(listOf(Result.success("reply")))
        val repo = ChatRepository(dao, client) { "key" }
        val result = repo.sendMessage("hello", "deepseek-flash")
        assertTrue(result is SendResult.Success)
        val msgs = dao.getAllChronologicalOnce()
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
        val repo = ChatRepository(dao, client) { "key" }
        val result = repo.sendMessage("hello", "deepseek-flash")
        assertTrue(result is SendResult.Failure)
        val msgs = dao.getAllChronologicalOnce()
        assertEquals(1, msgs.size)
        assertEquals("USER", msgs[0].role)
    }

    @Test
    fun sendMessage_blank_doesNotInsert() = runBlocking {
        val dao = FakeDao()
        val client = FakeClient(emptyList())
        val repo = ChatRepository(dao, client) { "key" }
        val result = repo.sendMessage("   ", "deepseek-flash")
        assertTrue(result is SendResult.Failure)
        assertEquals(0, dao.getAllChronologicalOnce().size)
    }

    @Test
    fun retryCompletion_doesNotDuplicateUser() = runBlocking {
        val dao = FakeDao()
        // first fail
        val client = FakeClient(listOf(Result.failure(Exception("fail")), Result.success("ok")))
        val repo = ChatRepository(dao, client) { "key" }
        repo.sendMessage("hello", "m")
        assertEquals(1, dao.getAllChronologicalOnce().size)
        val result = repo.retryCompletion("m")
        assertTrue(result is SendResult.Success)
        val msgs = dao.getAllChronologicalOnce()
        assertEquals(2, msgs.size)
        assertEquals(1, msgs.count { it.role == "USER" })
    }
}
