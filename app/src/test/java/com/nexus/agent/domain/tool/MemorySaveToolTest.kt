package com.nexus.agent.domain.tool

import com.nexus.agent.domain.policy.ApprovalGate
import com.nexus.agent.domain.policy.ApprovalRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySaveToolTest {

    @Test
    fun parsesContentAndSaves() = runBlocking {
        val saved = mutableListOf<String>()
        val tool = MemorySaveTool(save = { c -> saved += c; Result.success(Unit) })
        val result = tool.execute(buildJsonObject { put("content", JsonPrimitive("  I like tea  ")) })
        assertTrue(result is ToolResult.Success)
        assertEquals(listOf("I like tea"), saved)
    }

    @Test
    fun emptyContentIsFailure() = runBlocking {
        val tool = MemorySaveTool(save = { Result.success(Unit) })
        assertTrue(tool.execute(buildJsonObject { }) is ToolResult.Failure)
    }

    @Test
    fun saveFailureIsMappedToFailure() = runBlocking {
        val tool = MemorySaveTool(save = { Result.failure(RuntimeException("no neon")) })
        val result = tool.execute(buildJsonObject { put("content", JsonPrimitive("x")) })
        assertTrue(result is ToolResult.Failure)
    }

    @Test
    fun isCommitLevel() {
        val tool = MemorySaveTool(save = { Result.success(Unit) })
        assertEquals(RiskLevel.COMMIT, tool.riskLevel)
    }

    @Test
    fun defaultDeniedWithoutReceiptAndExecutesWithBoundReceipt() = runBlocking {
        val saved = mutableListOf<String>()
        val tool = MemorySaveTool(save = { c -> saved += c; Result.success(Unit) })
        val gate = ApprovalGate()
        val orch = ToolOrchestrator(mapOf("memory_save" to tool), gate)
        val input = buildJsonObject { put("content", JsonPrimitive("prefer tea")) }

        // No receipt → default-denied (the core least-privilege guarantee).
        assertTrue(orch.execute("memory_save", input) is ToolResult.Blocked)
        assertTrue(saved.isEmpty())

        // Receipt issued for a *different* action → still denied (bound to tool + arguments).
        val mismatched = gate.approve(ApprovalRequest("r0", "memory_save", "{\"content\":\"other\"}"))
        assertTrue(orch.execute("memory_save", input, mismatched) is ToolResult.Blocked)
        assertTrue(saved.isEmpty())

        // Valid receipt bound to this exact tool + arguments → executes once.
        val receipt = gate.approve(ApprovalRequest("r1", "memory_save", input.toString()))
        assertTrue(orch.execute("memory_save", input, receipt) is ToolResult.Success)
        assertEquals(listOf("prefer tea"), saved)

        // Replay the same receipt → denied (one-shot).
        assertTrue(orch.execute("memory_save", input, receipt) is ToolResult.Blocked)
        assertEquals(listOf("prefer tea"), saved)
    }
}
