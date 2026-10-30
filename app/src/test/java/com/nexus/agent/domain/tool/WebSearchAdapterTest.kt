package com.nexus.agent.domain.tool

import com.nexus.agent.domain.model.WebSearchResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchAdapterTest {

    private class FakeWebSearchTool(private val outcome: WebSearchOutcome) : WebSearchTool {
        var lastQuery: String = ""
        var lastCount: Int = -1
        override suspend fun search(query: String, count: Int): WebSearchOutcome {
            lastQuery = query
            lastCount = count
            return outcome
        }
    }

    @Test
    fun extractsQueryAndCount() = runBlocking {
        val fake = FakeWebSearchTool(WebSearchOutcome.Success("q", emptyList()))
        val tool = WebSearchAdapter(fake)
        tool.execute(
            buildJsonObject {
                put("query", JsonPrimitive("what is the capital of france"))
                put("count", JsonPrimitive("5"))
            }
        )
        assertEquals("what is the capital of france", fake.lastQuery)
        assertEquals(5, fake.lastCount)
    }

    @Test
    fun mapsResultsToSourcesWithUrl() = runBlocking {
        val result = WebSearchResult(
            title = "Paris",
            url = "https://example.com/paris",
            snippet = "Paris is the capital",
            summary = "summary",
            siteName = "Example",
            date = null,
            authority = 3,
            score = 0.9
        )
        val fake = FakeWebSearchTool(WebSearchOutcome.Success("q", listOf(result)))
        val tool = WebSearchAdapter(fake)
        val out = tool.execute(buildJsonObject { put("query", JsonPrimitive("x")) })
        assertTrue(out is ToolResult.Success)
        val success = out as ToolResult.Success
        assertEquals(1, success.sources.size)
        assertEquals("Paris", success.sources[0].title)
        assertEquals("https://example.com/paris", success.sources[0].url)
        assertEquals("Paris is the capital", success.sources[0].snippet)
        assertEquals("Example", success.sources[0].project)
    }

    @Test
    fun emptyQueryReturnsFailure() = runBlocking {
        val fake = FakeWebSearchTool(WebSearchOutcome.Success("q", emptyList()))
        val tool = WebSearchAdapter(fake)
        assertEquals(ToolResult.Failure("empty"), tool.execute(buildJsonObject { }))
    }

    @Test
    fun failureIsPropagated() = runBlocking {
        val fake = FakeWebSearchTool(WebSearchOutcome.Failure("no_key"))
        val tool = WebSearchAdapter(fake)
        val out = tool.execute(buildJsonObject { put("query", JsonPrimitive("x")) })
        assertEquals(ToolResult.Failure("no_key"), out)
    }
}
