package com.nexus.agent.domain.tool

import com.nexus.agent.domain.model.KnowledgeResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeSearchToolTest {

    private class FakeKnowledgeTool(private val result: KnowledgeSearchResult) : KnowledgeTool {
        var lastQuery: String = ""
        var lastTopK: Int = -1
        override suspend fun search(query: String, topK: Int): KnowledgeSearchResult {
            lastQuery = query
            lastTopK = topK
            return result
        }
    }

    @Test
    fun extractsQueryAndTopKFromInput() = runBlocking {
        val fake = FakeKnowledgeTool(KnowledgeSearchResult.Success("q", emptyList()))
        val tool = KnowledgeSearchTool(fake)
        tool.execute(
            buildJsonObject {
                put("query", JsonPrimitive("how is api key stored"))
                put("top_k", JsonPrimitive("5"))
            }
        )
        assertEquals("how is api key stored", fake.lastQuery)
        assertEquals(5, fake.lastTopK)
    }

    @Test
    fun missingTopKDefaultsToEight() = runBlocking {
        val fake = FakeKnowledgeTool(KnowledgeSearchResult.Success("q", emptyList()))
        val tool = KnowledgeSearchTool(fake)
        tool.execute(buildJsonObject { put("query", JsonPrimitive("x")) })
        assertEquals(8, fake.lastTopK)
    }

    @Test
    fun mapsResultsToSources() = runBlocking {
        val result = KnowledgeResult(
            project = "p1",
            file = "a.md",
            chunk = null,
            content = "excerpt A",
            score = 0.9
        )
        val fake = FakeKnowledgeTool(KnowledgeSearchResult.Success("q", listOf(result)))
        val tool = KnowledgeSearchTool(fake)
        val out = tool.execute(buildJsonObject { put("query", JsonPrimitive("x")) })
        assertTrue(out is ToolResult.Success)
        val success = out as ToolResult.Success
        assertEquals(1, success.sources.size)
        assertEquals("a.md", success.sources[0].title)
        assertEquals("excerpt A", success.sources[0].snippet)
        assertEquals("p1", success.sources[0].project)
    }

    @Test
    fun emptyQueryReturnsFailure() = runBlocking {
        val fake = FakeKnowledgeTool(KnowledgeSearchResult.Success("q", emptyList()))
        val tool = KnowledgeSearchTool(fake)
        val out = tool.execute(buildJsonObject { })
        assertEquals(ToolResult.Failure("empty"), out)
    }

    @Test
    fun knowledgeFailureIsPropagated() = runBlocking {
        val fake = FakeKnowledgeTool(KnowledgeSearchResult.Failure("no_url"))
        val tool = KnowledgeSearchTool(fake)
        val out = tool.execute(buildJsonObject { put("query", JsonPrimitive("x")) })
        assertEquals(ToolResult.Failure("no_url"), out)
    }
}
