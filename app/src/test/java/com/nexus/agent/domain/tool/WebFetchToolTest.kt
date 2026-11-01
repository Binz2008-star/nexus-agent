package com.nexus.agent.domain.tool

import com.nexus.agent.domain.model.WebFetchPage
import com.nexus.agent.domain.safety.UrlSafetyValidator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFetchToolTest {

    private fun input(url: String) = buildJsonObject { put("url", JsonPrimitive(url)) }

    @Test
    fun `returns success with cited source`() = runTest {
        val tool = WebFetchTool(fetch = { Result.success(WebFetchPage("Title", "https://example.com", "Hello world text")) })
        val result = tool.execute(input("https://example.com"))
        assertTrue(result is ToolResult.Success)
        val success = result as ToolResult.Success
        assertEquals("Hello world text", success.content)
        assertEquals(1, success.sources.size)
        assertEquals("Title", success.sources.first().title)
        assertEquals("https://example.com", success.sources.first().url)
    }

    @Test
    fun `returns failure for blank url`() = runTest {
        val tool = WebFetchTool(fetch = { error("should not be called") })
        val result = tool.execute(input("   "))
        assertTrue(result is ToolResult.Failure)
        assertEquals("empty", (result as ToolResult.Failure).errorCode)
    }

    @Test
    fun `maps blocked url to stable error code`() = runTest {
        val tool = WebFetchTool(fetch = { Result.failure(IllegalStateException(UrlSafetyValidator.ERR_BLOCKED)) })
        val result = tool.execute(input("http://localhost"))
        assertTrue(result is ToolResult.Failure)
        assertEquals("blocked_url", (result as ToolResult.Failure).errorCode)
    }
}
