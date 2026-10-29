package com.nexus.agent.data.repository

import com.nexus.agent.domain.model.KnowledgeResult
import com.nexus.agent.domain.tool.KnowledgeSearchResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KnowledgeRepositoryTest {

    private val sample = KnowledgeResult("proj", "file.md", "chunk1", "content", 0.9)

    private fun repo(searchApi: suspend (String, Int) -> Result<List<KnowledgeResult>>) =
        KnowledgeRepository(searchApi)

    @Test
    fun blankQueryReturnsEmptyError() = runBlocking {
        val r = repo { _, _ -> Result.success(listOf(sample)) }
        assertEquals(KnowledgeSearchResult.Failure("empty"), r.search("   ", 8))
    }

    @Test
    fun successReturnsResults() = runBlocking {
        val r = repo { _, _ -> Result.success(listOf(sample)) }
        val result = r.search("how to", 8)
        assertTrue(result is KnowledgeSearchResult.Success)
        assertTrue((result as KnowledgeSearchResult.Success).results.size == 1)
    }

    @Test
    fun emptyResultsAreStillSuccess() = runBlocking {
        val r = repo { _, _ -> Result.success(emptyList()) }
        val result = r.search("query", 8)
        assertTrue(result is KnowledgeSearchResult.Success)
        assertTrue((result as KnowledgeSearchResult.Success).results.isEmpty())
    }

    @Test
    fun topKIsClampedToRange() = runBlocking {
        var seen = -1
        val r = repo { _, k -> seen = k; Result.success(emptyList()) }
        r.search("q", 999)
        assertTrue(seen == 20)
        r.search("q", 0)
        assertTrue(seen == 1)
    }

    @Test
    fun httpErrorMapsToServer() = runBlocking {
        val r = repo { _, _ -> Result.failure(IOException("Knowledge API error 503")) }
        assertEquals(KnowledgeSearchResult.Failure("server"), r.search("q", 8))
    }

    @Test
    fun connectionErrorMapsToNetwork() = runBlocking {
        val r = repo { _, _ -> Result.failure(IOException("Failed to connect to /1.2.3.4:80")) }
        assertEquals(KnowledgeSearchResult.Failure("network"), r.search("q", 8))
    }

    @Test
    fun timeoutMapsToTimeout() = runBlocking {
        val r = repo { _, _ -> Result.failure(IOException("read timed out")) }
        assertEquals(KnowledgeSearchResult.Failure("timeout"), r.search("q", 8))
    }

    @Test
    fun malformedMapsToMalformed() = runBlocking {
        val r = repo { _, _ -> Result.failure(SerializationException("bad json")) }
        assertEquals(KnowledgeSearchResult.Failure("malformed"), r.search("q", 8))
    }

    @Test
    fun missingUrlMapsToNoUrl() = runBlocking {
        val r = repo { _, _ -> Result.failure(IOException("No knowledge base URL configured")) }
        assertEquals(KnowledgeSearchResult.Failure("no_url"), r.search("q", 8))
    }
}
