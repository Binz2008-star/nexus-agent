package com.nexus.agent.data.remote

import com.nexus.agent.domain.tool.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpBridgeTest {

    @Test
    fun searchDelegatesToBackend() = runBlocking {
        var called = false
        val bridge = McpBridge { _ -> called = true; ToolResult.Success("ok") }
        val result = bridge.call("search", buildJsonObject { put("query", "x") })
        assertTrue(result is ToolResult.Success)
        assertTrue(called)
    }

    @Test
    fun blockedToolsAreNeverCalled() = runBlocking {
        var called = false
        val bridge = McpBridge { _ -> called = true; ToolResult.Success("ok") }
        for (t in McpBridge.BLOCKED_TOOLS) {
            val result = bridge.call(t, buildJsonObject { })
            assertTrue("$t should be Blocked", result is ToolResult.Blocked)
        }
        assertTrue(!called)
    }

    @Test
    fun unknownToolReturnsFailure() = runBlocking {
        val bridge = McpBridge { _ -> ToolResult.Success("ok") }
        assertEquals(ToolResult.Failure("unknown_tool"), bridge.call("nope", buildJsonObject { }))
    }
}
