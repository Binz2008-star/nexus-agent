package com.nexus.agent.domain.tool

import com.nexus.agent.domain.policy.ApprovalGate
import com.nexus.agent.domain.policy.ApprovalReceipt
import com.nexus.agent.domain.policy.ApprovalRequest
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
        private val result: ToolResult,
        override val riskLevel: RiskLevel = RiskLevel.READ,
        override val requiredScopes: List<String> = emptyList()
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
    fun blockedToolsReturnBlocked() = runBlocking {
        val tool = FakeTool(result = ToolResult.Success("x"))
        val orch = ToolOrchestrator(mapOf("shell" to tool, "fake" to tool))
        for (blocked in ToolOrchestrator.BLOCKED_TOOLS) {
            val result = orch.execute(blocked, buildJsonObject { })
            assertTrue("$blocked should be Blocked", result is ToolResult.Blocked)
        }
        // Even if a blocked tool name is somehow registered, it must never execute.
        assertEquals(0, tool.calls)
    }

    @Test
    fun listsToolNames() {
        val orch = ToolOrchestrator(mapOf("a" to FakeTool("a", ToolResult.Success("x"))))
        assertTrue(orch.toolNames().contains("a"))
    }

    @Test
    fun commitLevelToolRequiresApprovalReceipt() = runBlocking {
        val tool = FakeTool(result = ToolResult.Success("x"), riskLevel = RiskLevel.COMMIT)
        val gate = ApprovalGate()
        val orch = ToolOrchestrator(mapOf("commit_tool" to tool), gate)

        // No receipt → default-denied.
        assertTrue(orch.execute("commit_tool", buildJsonObject { }) is ToolResult.Blocked)
        assertEquals(0, tool.calls)

        // A forged receipt (never issued by the gate) → denied.
        val forged = ApprovalReceipt("forged", 0L, Long.MAX_VALUE)
        assertTrue(orch.execute("commit_tool", buildJsonObject { }, forged) is ToolResult.Blocked)
        assertEquals(0, tool.calls)

        // A valid receipt issued by the gate → executes.
        val receipt = gate.approve(ApprovalRequest("r1", "commit_tool", "{}"))
        assertTrue(orch.execute("commit_tool", buildJsonObject { }, receipt) is ToolResult.Success)
        assertEquals(1, tool.calls)

        // A receipt issued for different arguments must not authorize.
        val mismatched = gate.approve(ApprovalRequest("r2", "commit_tool", "{\"to\":\"other\"}"))
        assertTrue(orch.execute("commit_tool", buildJsonObject { }, mismatched) is ToolResult.Blocked)
        assertEquals(1, tool.calls)

        // The same receipt cannot be replayed (one-shot).
        assertTrue(orch.execute("commit_tool", buildJsonObject { }, receipt) is ToolResult.Blocked)
        assertEquals(1, tool.calls)
    }

    @Test
    fun scopedToolIsBlockedWithoutGrantedScopes() = runBlocking {
        val tool = FakeTool("gmail", ToolResult.Success("x"), requiredScopes = listOf("gmail.readonly"))
        val orch = ToolOrchestrator(mapOf("gmail" to tool))

        // No granted scopes → default-denied.
        assertTrue(orch.execute("gmail", buildJsonObject { }) is ToolResult.Blocked)
        assertEquals(0, tool.calls)

        // Granted scopes → executes.
        val ok = orch.execute("gmail", buildJsonObject { }, grantedScopes = setOf("gmail.readonly"))
        assertTrue(ok is ToolResult.Success)
        assertEquals(1, tool.calls)
    }

    @Test
    fun capabilitiesExposeRiskAndScopes() {
        val read = FakeTool("read", ToolResult.Success("x"))
        val gmail = FakeTool("gmail", ToolResult.Success("x"), requiredScopes = listOf("gmail.readonly"))
        val orch = ToolOrchestrator(mapOf("read" to read, "gmail" to gmail))
        val caps = orch.capabilities().associateBy { it.name }
        assertEquals(RiskLevel.READ, caps["read"]?.riskLevel)
        assertEquals(listOf("gmail.readonly"), caps["gmail"]?.requiredScopes)
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
        assertTrue(prompt.contains("references"))
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
