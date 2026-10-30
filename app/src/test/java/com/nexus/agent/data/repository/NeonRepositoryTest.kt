package com.nexus.agent.data.repository

import com.nexus.agent.data.remote.NeonJdbc
import com.nexus.agent.domain.neon.SecondBrainSql
import com.nexus.agent.domain.tool.KnowledgeSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeonRepositoryTest {

    private class FakeJdbc(private val rows: Map<String, List<List<Any?>>> = emptyMap()) : NeonJdbc {
        val queries = mutableListOf<List<Any?>>()
        val executions = mutableListOf<List<Any?>>()
        var throwOnQuery: Throwable? = null

        override fun <T> query(url: String, sql: String, params: List<Any?>, rowMapper: (List<Any?>) -> T): List<T> {
            queries.add(params)
            throwOnQuery?.let { throw it }
            return (rows[sql] ?: emptyList()).map(rowMapper)
        }

        override fun execute(url: String, sql: String, params: List<Any?>): Int {
            executions.add(params)
            return 1
        }
    }

    private val readUrl = "jdbc:postgresql://h/db?sslmode=require&user=u&password=p"
    private val writeUrl = "jdbc:postgresql://h2/db2?sslmode=require&user=u&password=p"

    private fun repo(
        jdbc: NeonJdbc,
        read: suspend () -> String? = { readUrl },
        write: suspend () -> String? = { writeUrl }
    ) = NeonRepository(read, write, jdbc, Dispatchers.Unconfined)

    @Test
    fun emptyQueryFailsWithEmpty() = runTest {
        val result = repo(FakeJdbc()).search("   ", 8)
        assertTrue(result is KnowledgeSearchResult.Failure)
        assertEquals("empty", (result as KnowledgeSearchResult.Failure).errorCode)
    }

    @Test
    fun missingReadUrlFailsWithNoConnection() = runTest {
        val result = repo(FakeJdbc(), read = { null }).search("hello", 8)
        assertEquals("no_connection", (result as KnowledgeSearchResult.Failure).errorCode)
    }

    @Test
    fun sec001_noEmbeddedCredentialFallback() = runTest {
        // SEC-001 regression: with no runtime-configured Neon URL, every Neon operation must
        // degrade to a failure WITHOUT touching the JDBC layer — there is no compiled-in
        // credential fallback (the BuildConfig injection was removed).
        val jdbc = FakeJdbc()
        val r = repo(jdbc, read = { null }, write = { null })

        val search = r.search("hello", 8)
        assertEquals("no_connection", (search as KnowledgeSearchResult.Failure).errorCode)

        assertTrue(r.saveConversationMessage("s", "user", "hi").isFailure)
        assertTrue(r.saveMemory("fact", "x").isFailure)
        assertTrue(r.loadMemory().isFailure)

        // No JDBC call may have been attempted — proving there is no silent fallback.
        assertTrue(jdbc.queries.isEmpty())
        assertTrue(jdbc.executions.isEmpty())
    }

    @Test
    fun searchMergesChunksAndMemorySortedByScore() = runTest {
        val jdbc = FakeJdbc(
            mapOf(
                SecondBrainSql.SEARCH_CHUNKS_SQL to listOf(
                    listOf("projA", "a.py", "function", "content A", 0.8),
                    listOf("projB", "b.py", "class", "content B", 0.5)
                ),
                SecondBrainSql.SEARCH_MEMORY_SQL to listOf(
                    listOf("fact", "content C", 0.9)
                )
            )
        )
        val success = repo(jdbc).search("hello", 8) as KnowledgeSearchResult.Success
        assertEquals(3, success.results.size)
        assertEquals("memory", success.results[0].project)
        assertEquals("content A", success.results[1].content)
        assertEquals("content B", success.results[2].content)
    }

    @Test
    fun topKIsClampedToMax() = runTest {
        val jdbc = FakeJdbc(
            mapOf(
                SecondBrainSql.SEARCH_CHUNKS_SQL to emptyList(),
                SecondBrainSql.SEARCH_MEMORY_SQL to emptyList()
            )
        )
        repo(jdbc).search("hello", 999)
        assertEquals(20, jdbc.queries[0][2] as Int)
        assertEquals(20, jdbc.queries[1][2] as Int)
    }

    @Test
    fun queryExceptionMapsToErrorCode() = runTest {
        val jdbc = FakeJdbc().apply { throwOnQuery = RuntimeException("Connection refused") }
        val result = repo(jdbc).search("hello", 8)
        assertEquals("network", (result as KnowledgeSearchResult.Failure).errorCode)
    }

    @Test
    fun saveMemoryPassesTagsLiteral() = runTest {
        val jdbc = FakeJdbc()
        val result = repo(jdbc).saveMemory("fact", "likes coffee", listOf("pref", "lang"))
        assertTrue(result.isSuccess)
        assertEquals(listOf("fact", "likes coffee", "{\"pref\",\"lang\"}"), jdbc.executions[0])
    }

    @Test
    fun saveConversationPassesArgs() = runTest {
        val jdbc = FakeJdbc()
        val result = repo(jdbc).saveConversationMessage("sess-1", "user", "hello")
        assertTrue(result.isSuccess)
        assertEquals(listOf("sess-1", "user", "hello"), jdbc.executions[0])
    }

    @Test
    fun saveMemoryWithoutWriteUrlFails() = runTest {
        val result = repo(FakeJdbc(), write = { null }).saveMemory("fact", "x")
        assertTrue(result.isFailure)
    }

    @Test
    fun mirrorSwallowsFailures() = runTest {
        // No write URL → saveConversationMessage fails → mirror must still not throw.
        repo(FakeJdbc(), write = { null }).mirror("sess-1", "user", "hi")
    }
}
