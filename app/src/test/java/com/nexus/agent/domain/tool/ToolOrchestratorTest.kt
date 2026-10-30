package com.nexus.agent.domain.tool

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOrchestratorTest {

    private class FakeTool(
        override val name: String = "fake",
        private val result: ToolResult
    ) : Tool {
        var calls = 0
        override suspend fun execute(input: JsonObject): ToolResult {
            calls++
            return result
        }
    }

    @Test
    fun runsToolAndReturnsResult() = runBlocking {
        val tool = FakeTool(result = ToolResult.Success("excerpt", listOf(ToolSource("a.md"))))
        val orch = ToolOrchestrator(mapOf("fake" to tool))
        val result = orch.execute("fake", buildJsonObject { put("query", JsonPrimitive("x")) })
        assertTrue(result is ToolResult.Success)
        assertEquals(1, tool.calls)
        assertEquals(1, (result as ToolResult.Success).sources.size)
    }

    @Test
    fun unknownToolReturnsFailure() = runBlocking {
        val orch = ToolOrchestrator(emptyMap())
        assertEquals(
            ToolResult.Failure("unknown_tool"),
            orch.execute("nope", buildJsonObject { })
        )
    }

    @Test
    fun listsToolNames() {
        val orch = ToolOrchestrator(mapOf("a" to FakeTool("a", ToolResult.Success("x"))))
        assertTrue(orch.toolNames().contains("a"))
    }

    @Test
    fun buildContextPromptIncludesEachSourceTitleAndSnippet() {
        val prompt = buildContextPrompt(
            listOf(
                ToolSource("a.md", snippet = "excerpt A"),
                ToolSource("b.md", snippet = "excerpt B")
            )
        )
        assertTrue(prompt.contains("Source: a.md"))
        assertTrue(prompt.contains("excerpt A"))
        assertTrue(prompt.contains("Source: b.md"))
        assertTrue(prompt.contains("excerpt B"))
        assertTrue(prompt.contains("Cite sources"))
    }

    @Test
    fun buildContextPromptEmptySourcesHasNoSourceLines() {
        val prompt = buildContextPrompt(emptyList())
        assertTrue(prompt.contains("excerpts"))
        assertTrue(!prompt.contains("Source:"))
    }

    @Test
    fun buildContextPromptIncludesUrlWhenPresent() {
        val prompt = buildContextPrompt(
            listOf(ToolSource("Page", snippet = "excerpt", url = "https://x.com/a"))
        )
        assertTrue(prompt.contains("https://x.com/a"))
    }
}
