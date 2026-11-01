package com.nexus.agent.domain.orchestration

import com.nexus.agent.domain.model.ChatMessage
import com.nexus.agent.domain.policy.ApprovalGate
import com.nexus.agent.domain.tool.RiskLevel
import com.nexus.agent.domain.tool.Tool
import com.nexus.agent.domain.tool.ToolCall
import com.nexus.agent.domain.tool.ToolOrchestrator
import com.nexus.agent.domain.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskContractTest {
    private class FakeTool(
        override val name: String = "read_tool",
        override val riskLevel: RiskLevel = RiskLevel.READ,
        override val requiredScopes: List<String> = emptyList()
    ) : Tool {
        var executions = 0
        override suspend fun execute(input: JsonObject): ToolResult {
            executions++
            return ToolResult.Success("ok")
        }
    }

    private val history = listOf(ChatMessage(ChatMessage.Role.USER, "perform a task"))

    @Test fun legacyChatAnswersWithoutAction() = runTest {
        val outcome = ToolLoop(ToolOrchestrator(emptyMap())).run(
            history, "model", emptyList(),
            propose = { _, _, _ -> Result.success(ToolProposal.Answer("hello")) }
        )
        assertEquals("hello", (outcome as ToolLoop.Outcome.Answered).content)
        assertTrue(outcome.evidence.isEmpty())
    }

    @Test fun requiredActionRejectsCompletionClaimsWithoutExecution() = runTest {
        var proposals = 0
        val outcome = ToolLoop(ToolOrchestrator(emptyMap())).run(
            history, "model", emptyList(),
            propose = { h, _, _ ->
                proposals++
                if (proposals > 1) assertTrue(h.any {
                    it.role == ChatMessage.Role.SYSTEM &&
                        it.text.contains("A statement or plan is not execution evidence")
                })
                Result.success(ToolProposal.Answer("Done, everything is finished."))
            },
            taskContract = AgentTaskContract(actionRequired = true)
        )
        assertEquals("failure_budget_exceeded", (outcome as ToolLoop.Outcome.Failed).reason)
        assertEquals(3, proposals)
    }

    @Test fun successfulExecutionAllowsBoundedActionCompletion() = runTest {
        val tool = FakeTool()
        var round = 0
        val outcome = ToolLoop(ToolOrchestrator(mapOf("read_tool" to tool))).run(
            history, "model", emptyList(),
            propose = { _, _, _ ->
                round++
                Result.success(if (round == 1) ToolProposal.Calls(listOf(ToolCall("read_tool")))
                    else ToolProposal.Answer("Read completed"))
            },
            taskContract = AgentTaskContract(actionRequired = true)
        )
        val done = outcome as ToolLoop.Outcome.Answered
        assertEquals(1, tool.executions)
        assertEquals(listOf(ActionEvidence("read_tool", ActionEvidenceStatus.EXECUTED)), done.evidence)
    }

    @Test fun successfulToolResultIsNotIndependentVerification() = runTest {
        val tool = FakeTool()
        var round = 0
        val outcome = ToolLoop(ToolOrchestrator(mapOf("read_tool" to tool))).run(
            history, "model", emptyList(),
            propose = { _, _, _ ->
                round++
                Result.success(if (round == 1) ToolProposal.Calls(listOf(ToolCall("read_tool")))
                    else ToolProposal.Answer("Done"))
            },
            taskContract = AgentTaskContract(actionRequired = true, independentVerificationRequired = true)
        )
        assertEquals("failure_budget_exceeded", (outcome as ToolLoop.Outcome.Failed).reason)
        assertEquals(1, tool.executions)
    }

    @Test fun independentlyVerifiedPostconditionAllowsCompletion() = runTest {
        val tool = FakeTool()
        var round = 0
        var checks = 0
        val outcome = ToolLoop(ToolOrchestrator(mapOf("read_tool" to tool))).run(
            history, "model", emptyList(),
            propose = { _, _, _ ->
                round++
                Result.success(if (round == 1) ToolProposal.Calls(listOf(ToolCall("read_tool")))
                    else ToolProposal.Answer("Verified result"))
            },
            taskContract = AgentTaskContract(actionRequired = true, independentVerificationRequired = true),
            independentlyVerify = { call, _ ->
                checks++
                call.name == "read_tool"
            }
        )
        val done = outcome as ToolLoop.Outcome.Answered
        assertEquals(1, checks)
        assertEquals(ActionEvidenceStatus.VERIFIED, done.evidence.single().status)
    }

    @Test fun newerFailedPostconditionInvalidatesHistoricalVerification() = runTest {
        val tool = FakeTool()
        var proposals = 0
        var checks = 0
        val outcome = ToolLoop(ToolOrchestrator(mapOf("read_tool" to tool))).run(
            history, "model", emptyList(),
            propose = { _, _, _ ->
                proposals++
                Result.success(if (proposals == 1) {
                    ToolProposal.Calls(listOf(ToolCall("read_tool"), ToolCall("read_tool")))
                } else ToolProposal.Answer("Claimed completion"))
            },
            taskContract = AgentTaskContract(
                actionRequired = true,
                independentVerificationRequired = true
            ),
            independentlyVerify = { _, _ ->
                checks++
                checks == 1 // First postcondition passes; the second check fails.
            }
        )
        assertTrue(outcome is ToolLoop.Outcome.Failed)
        assertEquals(2, checks)
        assertEquals(2, tool.executions)
    }

    @Test fun laterVerifiedCheckCanRestoreCurrentPostcondition() = runTest {
        val tool = FakeTool()
        var proposals = 0
        var checks = 0
        val outcome = ToolLoop(ToolOrchestrator(mapOf("read_tool" to tool))).run(
            history, "model", emptyList(),
            propose = { _, _, _ ->
                proposals++
                Result.success(if (proposals == 1) {
                    ToolProposal.Calls(listOf(
                        ToolCall("read_tool"), ToolCall("read_tool"), ToolCall("read_tool")
                    ))
                } else ToolProposal.Answer("Current state verified"))
            },
            taskContract = AgentTaskContract(
                actionRequired = true,
                independentVerificationRequired = true
            ),
            independentlyVerify = { _, _ ->
                checks++
                checks != 2
            }
        )
        val done = outcome as ToolLoop.Outcome.Answered
        assertEquals("Current state verified", done.content)
        assertEquals(3, checks)
        assertEquals(
            listOf(ActionEvidenceStatus.VERIFIED, ActionEvidenceStatus.EXECUTED, ActionEvidenceStatus.VERIFIED),
            done.evidence.map { it.status }
        )
    }

    @Test fun laterBlockedActionCannotReusePreviousVerification() = runTest {
        val read = FakeTool("read_tool")
        val blocked = FakeTool("needs_scope", requiredScopes = listOf("test.permission"))
        val outcome = ToolLoop(
            ToolOrchestrator(mapOf(read.name to read, blocked.name to blocked))
        ).run(
            history, "model", emptyList(),
            propose = { h, _, _ ->
                if (h.none { it.text.contains("tool_results") }) {
                    Result.success(ToolProposal.Calls(listOf(
                        ToolCall("read_tool"), ToolCall("needs_scope")
                    )))
                } else {
                    Result.success(ToolProposal.Answer("Claimed completion"))
                }
            },
            taskContract = AgentTaskContract(
                actionRequired = true,
                independentVerificationRequired = true
            ),
            independentlyVerify = { _, _ -> true }
        )
        assertTrue(outcome is ToolLoop.Outcome.Failed)
        assertEquals(0, blocked.executions)
    }

    @Test fun deniedApprovalNeverCountsAsExecution() = runTest {
        val tool = FakeTool("unsafe_tool", RiskLevel.COMMIT)
        val loop = ToolLoop(ToolOrchestrator(mapOf(tool.name to tool), ApprovalGate()),
            requestApproval = { null })
        var round = 0
        val outcome = loop.run(history, "model", emptyList(),
            propose = { _, _, _ ->
                round++
                Result.success(if (round == 1) ToolProposal.Calls(listOf(ToolCall(tool.name)))
                    else ToolProposal.Answer("done"))
            },
            taskContract = AgentTaskContract(actionRequired = true)
        )
        assertTrue(outcome is ToolLoop.Outcome.Failed)
        assertEquals(0, tool.executions)
    }

    @Test fun scopeDenialCannotSatisfyContract() = runTest {
        val tool = FakeTool(requiredScopes = listOf("gmail.readonly"))
        var round = 0
        val outcome = ToolLoop(ToolOrchestrator(mapOf(tool.name to tool))).run(
            history, "model", emptyList(),
            propose = { _, _, _ ->
                round++
                Result.success(if (round == 1) ToolProposal.Calls(listOf(ToolCall(tool.name)))
                    else ToolProposal.Answer("done"))
            },
            taskContract = AgentTaskContract(actionRequired = true)
        )
        assertTrue(outcome is ToolLoop.Outcome.Failed)
        assertEquals(0, tool.executions)
    }

    @Test fun actionCountCannotExceedContractLimit() = runTest {
        val tool = FakeTool()
        val outcome = ToolLoop(ToolOrchestrator(mapOf(tool.name to tool))).run(
            history, "model", emptyList(),
            propose = { _, _, _ ->
                Result.success(ToolProposal.Calls(listOf(
                    ToolCall(tool.name), ToolCall(tool.name), ToolCall(tool.name)
                )))
            },
            taskContract = AgentTaskContract(actionRequired = true, maxActions = 2)
        )
        assertEquals("action_budget_exceeded", (outcome as ToolLoop.Outcome.Failed).reason)
        assertEquals(2, tool.executions)
    }

    @Test fun invalidContractRejectedBeforeModel() {
        var thrown = false
        try {
            AgentTaskContract(independentVerificationRequired = true)
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
        assertFalse(AgentTaskContract().actionRequired)
    }
}
