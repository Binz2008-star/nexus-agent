package com.nexus.agent.domain.orchestration

import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.domain.policy.ApprovalGate
import com.nexus.agent.domain.tool.RiskLevel
import com.nexus.agent.domain.tool.Tool
import com.nexus.agent.domain.tool.ToolCall
import com.nexus.agent.domain.tool.ToolOrchestrator
import com.nexus.agent.domain.tool.ToolResult
import com.nexus.agent.domain.tool.ToolSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopTest {

    private class FakeTool(
        override val name: String,
        private val result: ToolResult,
        override val riskLevel: RiskLevel = RiskLevel.READ
    ) : Tool {
        var calls = 0
        override suspend fun execute(input: JsonObject): ToolResult {
            calls++
            return result
        }
    }

    @Test
    fun answersDirectlyWhenProposalIsAnswer() = runTest {
        val loop = ToolLoop(ToolOrchestrator(emptyMap()))
        val outcome = loop.run(
            history = listOf(ChatMessage(ChatMessage.Role.USER, "hi")),
            model = "m",
            schemas = emptyList(),
            propose = { _, _, _ -> Result.success(ToolProposal.Answer("hello!")) }
        )
        val answered = outcome as ToolLoop.Outcome.Answered
        assertEquals("hello!", answered.content)
        assertTrue(answered.sources.isEmpty())
    }

    @Test
    fun executesToolThenAnswers() = runTest {
        val tool = FakeTool("web_search", ToolResult.Success("excerpt", listOf(ToolSource("a.md"))))
        val loop = ToolLoop(ToolOrchestrator(mapOf("web_search" to tool)))
        var round = 0
        val outcome = loop.run(
            history = listOf(ChatMessage(ChatMessage.Role.USER, "weather?")),
            model = "m",
            schemas = emptyList(),
            propose = { _, _, _ ->
                round++
                if (round == 1) {
                    Result.success(ToolProposal.Calls(listOf(ToolCall("web_search", mapOf("query" to "dubai")))))
                } else {
                    Result.success(ToolProposal.Answer("it is sunny"))
                }
            }
        )
        val answered = outcome as ToolLoop.Outcome.Answered
        assertEquals("it is sunny", answered.content)
        assertEquals(1, answered.sources.size)
        assertEquals(1, tool.calls)
    }

    @Test
    fun failsWhenProposalFails() = runTest {
        val loop = ToolLoop(ToolOrchestrator(emptyMap()))
        val outcome = loop.run(
            history = emptyList(), model = "m", schemas = emptyList(),
            propose = { _, _, _ -> Result.failure(Exception("boom")) }
        )
        assertEquals("completion_failed", (outcome as ToolLoop.Outcome.Failed).reason)
    }

    @Test
    fun boundedByMaxRounds() = runTest {
        val tool = FakeTool("web_search", ToolResult.Success("x"))
        val loop = ToolLoop(ToolOrchestrator(mapOf("web_search" to tool)), maxRounds = 2)
        val outcome = loop.run(
            history = emptyList(), model = "m", schemas = emptyList(),
            propose = { _, _, _ ->
                Result.success(ToolProposal.Calls(listOf(ToolCall("web_search", mapOf("query" to "q")))))
            }
        )
        assertEquals("max_rounds_exceeded", (outcome as ToolLoop.Outcome.Failed).reason)
        assertEquals(2, tool.calls)
    }

    @Test
    fun executesMultipleCallsInOneRound() = runTest {
        val web = FakeTool("web_search", ToolResult.Success("web", listOf(ToolSource("web.md"))))
        val brain = FakeTool("second_brain_search", ToolResult.Success("brain", listOf(ToolSource("brain.md"))))
        val loop = ToolLoop(ToolOrchestrator(mapOf("web_search" to web, "second_brain_search" to brain)))
        var round = 0
        val outcome = loop.run(
            history = emptyList(), model = "m", schemas = emptyList(),
            propose = { _, _, _ ->
                round++
                if (round == 1) {
                    Result.success(ToolProposal.Calls(listOf(
                        ToolCall("web_search", mapOf("query" to "a")),
                        ToolCall("second_brain_search", mapOf("query" to "b"))
                    )))
                } else Result.success(ToolProposal.Answer("done"))
            }
        )
        val answered = outcome as ToolLoop.Outcome.Answered
        assertEquals(2, answered.sources.size)
        assertEquals(1, web.calls)
        assertEquals(1, brain.calls)
    }

    @Test
    fun commitToolWithoutApprovalIsNotExecuted() = runTest {
        val commit = FakeTool("commit_tool", ToolResult.Success("x"), riskLevel = RiskLevel.COMMIT)
        val gate = ApprovalGate()
        val loop = ToolLoop(ToolOrchestrator(mapOf("commit_tool" to commit), gate), requestApproval = { null })
        var round = 0
        val outcome = loop.run(
            history = emptyList(), model = "m", schemas = emptyList(),
            propose = { _, _, _ ->
                round++
                if (round == 1) Result.success(ToolProposal.Calls(listOf(ToolCall("commit_tool", mapOf("x" to "y")))))
                else Result.success(ToolProposal.Answer("done"))
            }
        )
        assertTrue(outcome is ToolLoop.Outcome.Answered)
        assertEquals(0, commit.calls) // never executed without approval
    }

    @Test
    fun commitToolWithApprovalExecutes() = runTest {
        val commit = FakeTool("commit_tool", ToolResult.Success("committed", listOf(ToolSource("commit.md"))), riskLevel = RiskLevel.COMMIT)
        val gate = ApprovalGate()
        val loop = ToolLoop(ToolOrchestrator(mapOf("commit_tool" to commit), gate), requestApproval = { req -> gate.approve(req) })
        var round = 0
        val outcome = loop.run(
            history = emptyList(), model = "m", schemas = emptyList(),
            propose = { _, _, _ ->
                round++
                if (round == 1) Result.success(ToolProposal.Calls(listOf(ToolCall("commit_tool", mapOf("x" to "y")))))
                else Result.success(ToolProposal.Answer("done"))
            }
        )
        val answered = outcome as ToolLoop.Outcome.Answered
        assertEquals(1, commit.calls)
        assertEquals(1, answered.sources.size)
    }
}
