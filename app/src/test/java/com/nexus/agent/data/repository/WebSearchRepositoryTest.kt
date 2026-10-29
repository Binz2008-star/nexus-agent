package com.nexus.agent.data.repository

import com.nexus.agent.domain.model.WebSearchResult
import com.nexus.agent.domain.tool.WebSearchOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WebSearchRepositoryTest {

    private val sample = WebSearchResult(
        title = "Cars", url = "https://example.com/cars", snippet = "snip",
        summary = "sum", siteName = "Example", date = null, authority = 3, score = 2.4
    )

    private fun repo(searchApi: suspend (String, Int) -> Result<List<WebSearchResult>>) =
        WebSearchRepository(searchApi)

    @Test
    fun blankQueryReturnsEmptyError() = runBlocking {
        val r = repo { _, _ -> Result.success(listOf(sample)) }
        assertEquals(WebSearchOutcome.Failure("empty"), r.search("   ", 10))
    }

    @Test
    fun successReturnsResults() = runBlocking {
        val r = repo { _, _ -> Result.success(listOf(sample)) }
        val result = r.search("cars", 10)
        assertTrue(result is WebSearchOutcome.Success)
        assertTrue((result as WebSearchOutcome.Success).results.size == 1)
    }

    @Test
    fun emptyResultsAreStillSuccess() = runBlocking {
        val r = repo { _, _ -> Result.success(emptyList()) }
        val result = r.search("cars", 10)
        assertTrue(result is WebSearchOutcome.Success)
        assertTrue((result as WebSearchOutcome.Success).results.isEmpty())
    }

    @Test
    fun countIsClampedToRange() = runBlocking {
        var seen = -1
        val r = repo { _, c -> seen = c; Result.success(emptyList()) }
        r.search("q", 999)
        assertTrue(seen == 50)
        r.search("q", 0)
        assertTrue(seen == 1)
    }

    @Test
    fun authErrorMapsToAuth() = runBlocking {
        val r = repo { _, _ -> Result.failure(IOException("DeepSeek search error 401")) }
        assertEquals(WebSearchOutcome.Failure("auth"), r.search("q", 10))
    }

    @Test
    fun quotaErrorMapsToQuota() = runBlocking {
        val r = repo { _, _ -> Result.failure(IOException("DeepSeek search error 402")) }
        assertEquals(WebSearchOutcome.Failure("quota"), r.search("q", 10))
    }

    @Test
    fun rateLimitMapsToRate() = runBlocking {
        val r = repo { _, _ -> Result.failure(IOException("DeepSeek search error 429")) }
        assertEquals(WebSearchOutcome.Failure("rate"), r.search("q", 10))
    }

    @Test
    fun networkErrorMapsToNetwork() = runBlocking {
        val r = repo { _, _ -> Result.failure(IOException("Failed to connect to host")) }
        assertEquals(WebSearchOutcome.Failure("network"), r.search("q", 10))
    }

    @Test
    fun malformedMapsToMalformed() = runBlocking {
        val r = repo { _, _ -> Result.failure(SerializationException("bad json")) }
        assertEquals(WebSearchOutcome.Failure("malformed"), r.search("q", 10))
    }
}
