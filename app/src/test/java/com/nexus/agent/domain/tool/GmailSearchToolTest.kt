package com.nexus.agent.domain.tool

import com.nexus.agent.domain.model.GmailMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailSearchToolTest {

    private class FakeGmail(private val outcome: GmailSearchResult) : GmailTool {
        var lastQuery: String = ""
        var lastMax: Int = -1
        override suspend fun searchInbox(query: String, maxResults: Int): GmailSearchResult {
            lastQuery = query
            lastMax = maxResults
            return outcome
        }
    }

    @Test
    fun extractsQueryAndMaxResults() = runBlocking {
        val fake = FakeGmail(GmailSearchResult.Success("q", emptyList()))
        val tool = GmailSearchTool(fake)
        tool.execute(
            buildJsonObject {
                put("query", JsonPrimitive("invoice"))
                put("max_results", JsonPrimitive("10"))
            }
        )
        assertEquals("invoice", fake.lastQuery)
        assertEquals(10, fake.lastMax)
    }

    @Test
    fun mapsMessagesToSources() = runBlocking {
        val msg = GmailMessage(
            id = "1", threadId = "t1",
            subject = "Invoice", from = "a@b.com", snippet = "Your invoice is attached"
        )
        val fake = FakeGmail(GmailSearchResult.Success("invoice", listOf(msg)))
        val tool = GmailSearchTool(fake)
        val out = tool.execute(buildJsonObject { put("query", JsonPrimitive("invoice")) })
        assertTrue(out is ToolResult.Success)
        val success = out as ToolResult.Success
        assertEquals(1, success.sources.size)
        assertEquals("Invoice", success.sources[0].title)
        assertEquals("Your invoice is attached", success.sources[0].snippet)
    }

    @Test
    fun emptyQueryReturnsFailure() = runBlocking {
        val tool = GmailSearchTool(FakeGmail(GmailSearchResult.Success("q", emptyList())))
        assertEquals(ToolResult.Failure("empty"), tool.execute(buildJsonObject { }))
    }

    @Test
    fun failureIsPropagated() = runBlocking {
        val tool = GmailSearchTool(FakeGmail(GmailSearchResult.Failure("no_token")))
        val out = tool.execute(buildJsonObject { put("query", JsonPrimitive("x")) })
        assertEquals(ToolResult.Failure("no_token"), out)
    }
}
