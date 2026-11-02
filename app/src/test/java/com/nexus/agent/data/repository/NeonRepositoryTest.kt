package com.nexus.agent.data.repository

import com.nexus.agent.data.remote.NeonJdbc
import com.nexus.agent.domain.model.KnowledgeResult
import com.nexus.agent.domain.neon.NeonBackendClient
import com.nexus.agent.domain.neon.NeonMemoryItem
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
    ) = NeonRepository(read, write, jdbc, Dispatchers.Unconfined, allowLegacyJdbc = true)

    private class FakeBackend : NeonBackendClient {
        var searchResult: KnowledgeSearchResult = KnowledgeSearchResult.Success("", emptyList())
        var searchCalls = 0
        var saveMemoryCalls = 0
        var loadMemoryCalls = 0
        var mirrorCalls = 0
        var loadMemoryResult: Result<List<NeonMemoryItem>> = Result.success(emptyList())

        override suspend fun search(query: String, topK: Int): KnowledgeSearchResult {
            searchCalls++
            return searchResult
        }

        override suspend fun saveMemory(type: String, content: String, tags: List<String>): Result<Unit> {
            saveMemoryCalls++
            return Result.success(Unit)
        }

        override suspend fun loadMemory(limit: Int): Result<List<NeonMemoryItem>> {
            loadMemoryCalls++
            return loadMemoryResult
        }

        override suspend fun mirror(sessionId: String, role: String, content: String): Result<Unit> {
            mirrorCalls++
            return Result.success(Unit)
        }
    }

    private fun repoHttp(
        jdbc: NeonJdbc,
        backend: NeonBackendClient?,
        useBackend: suspend () -> Boolean = { true },
        read: suspend () -> String? = { readUrl },
        write: suspend () -> String? = { writeUrl }
    ) = NeonRepository(read, write, jdbc, Dispatchers.Unconfined, backend, useBackend, allowLegacyJdbc = true)

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

    @Test
    fun recallMemorySelectsRelevantAndBounded() = runTest {
        val jdbc = FakeJdbc(
            mapOf(
                SecondBrainSql.SELECT_MEMORY_SQL to listOf(
                    listOf("fact", "The user's favorite color is blue"),
                    listOf("fact", "The user lives in Dubai"),
                    listOf("fact", "The user's favorite color is green")
                )
            )
        )
        val items = repo(jdbc).recallMemory("favorite color", 2).getOrThrow()
        assertEquals(2, items.size)
        assertTrue(items.all { it.content.contains("favorite color", ignoreCase = true) })
    }

    @Test
    fun recallMemoryWithoutWriteUrlFails() = runTest {
        assertTrue(repo(FakeJdbc(), write = { null }).recallMemory("q").isFailure)
    }

    @Test
    fun recallMemoryJdbcErrorReturnsFailureNotThrow() = runTest {
        val jdbc = FakeJdbc().apply { throwOnQuery = RuntimeException("Connection refused") }
        assertTrue(repo(jdbc).recallMemory("q").isFailure)
    }

    @Test
    fun backendFlagRoutesSearchToHttpAndSkipsJdbc() = runTest {
        val jdbc = FakeJdbc()
        val backend = FakeBackend().apply {
            searchResult = KnowledgeSearchResult.Success(
                "hello", listOf(KnowledgeResult("proj", "f.txt", "txt", "hi", 0.9))
            )
        }
        val result = repoHttp(jdbc, backend).search("hello", 8)
        assertTrue(result is KnowledgeSearchResult.Success)
        assertEquals(1, (result as KnowledgeSearchResult.Success).results.size)
        assertEquals(1, backend.searchCalls)
        assertTrue(jdbc.queries.isEmpty())
    }

    @Test
    fun backendFlagRoutesSaveAndLoadToHttp() = runTest {
        val jdbc = FakeJdbc()
        val backend = FakeBackend()
        val r = repoHttp(jdbc, backend)
        assertTrue(r.saveMemory("fact", "x", listOf("t")).isSuccess)
        assertTrue(r.saveConversationMessage("s", "user", "hi").isSuccess)
        assertTrue(r.loadMemory().isSuccess)
        assertEquals(1, backend.saveMemoryCalls)
        assertEquals(1, backend.mirrorCalls)
        assertEquals(1, backend.loadMemoryCalls)
        assertTrue(jdbc.executions.isEmpty())
        assertTrue(jdbc.queries.isEmpty())
    }

    @Test
    fun backendFlagOffStillUsesJdbc() = runTest {
        val jdbc = FakeJdbc(
            mapOf(
                SecondBrainSql.SEARCH_CHUNKS_SQL to emptyList(),
                SecondBrainSql.SEARCH_MEMORY_SQL to emptyList()
            )
        )
        val backend = FakeBackend()
        repoHttp(jdbc, backend, useBackend = { false }).search("hello", 8)
        assertEquals(0, backend.searchCalls)
        assertEquals(2, jdbc.queries.size)
    }

    @Test
    fun backendFlagOnWithoutClientFailsClosedForEveryOperation() = runTest {
        val jdbc = FakeJdbc()
        val repo = repoHttp(jdbc, backend = null, useBackend = { true })
        val searched = repo.search("hello", 8)
        assertEquals("no_connection", (searched as KnowledgeSearchResult.Failure).errorCode)
        assertTrue(repo.saveMemory("fact", "test").isFailure)
        assertTrue(repo.saveConversationMessage("s", "user", "test").isFailure)
        assertTrue(repo.loadMemory().isFailure)
        assertTrue(jdbc.queries.isEmpty())
        assertTrue(jdbc.executions.isEmpty())
    }
    @Test
    fun releaseBuildNeverUsesDirectJdbcForAnyOperation() = runTest {
        val jdbc = FakeJdbc()
        var urlLookups = 0
        val r = NeonRepository(
            readUrlProvider = { urlLookups++; readUrl },
            writeUrlProvider = { urlLookups++; writeUrl },
            jdbc = jdbc,
            ioDispatcher = Dispatchers.Unconfined,
            useBackendProvider = { false },
            allowLegacyJdbc = false
        )
        val search = r.search("hello", 8)
        assertEquals(
            NeonRepository.ERROR_DIRECT_JDBC_DISABLED,
            (search as KnowledgeSearchResult.Failure).errorCode
        )
        assertTrue(r.saveMemory("fact", "sensitive").isFailure)
        assertTrue(r.saveConversationMessage("s", "user", "sensitive").isFailure)
        assertTrue(r.loadMemory(4).isFailure)
        assertTrue(r.recallMemory("sensitive").isFailure)
        assertEquals(0, urlLookups)
        assertTrue(jdbc.queries.isEmpty())
        assertTrue(jdbc.executions.isEmpty())
    }

    @Test
    fun releaseBuildStillAllowsConfiguredHttpsBackend() = runTest {
        val jdbc = FakeJdbc()
        val backend = FakeBackend()
        val r = NeonRepository(
            readUrlProvider = { readUrl },
            writeUrlProvider = { writeUrl },
            jdbc = jdbc,
            ioDispatcher = Dispatchers.Unconfined,
            httpClient = backend,
            useBackendProvider = { true },
            allowLegacyJdbc = false
        )
        assertTrue(r.search("hello", 8) is KnowledgeSearchResult.Success)
        assertTrue(r.saveMemory("fact", "a").isSuccess)
        assertTrue(r.saveConversationMessage("s", "user", "test").isSuccess)
        assertTrue(r.loadMemory().isSuccess)
        assertEquals(1, backend.searchCalls)
        assertEquals(1, backend.saveMemoryCalls)
        assertEquals(1, backend.mirrorCalls)
        assertEquals(1, backend.loadMemoryCalls)
        assertTrue(jdbc.queries.isEmpty())
        assertTrue(jdbc.executions.isEmpty())
    }

}
