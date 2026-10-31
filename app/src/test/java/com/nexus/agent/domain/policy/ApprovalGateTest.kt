package com.nexus.agent.domain.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalGateTest {

    @Test
    fun approveThenAuthorizeThenConsumeIsOneShot() {
        var now = 0L
        val gate = ApprovalGate(clock = { now })
        val receipt = gate.approve(ApprovalRequest("r1", "calendar.create", "args"))
        assertTrue(gate.isAuthorized(receipt, "calendar.create", "args"))
        gate.consume(receipt)
        // Replay must be rejected.
        assertFalse(gate.isAuthorized(receipt, "calendar.create", "args"))
    }

    @Test
    fun receiptIsBoundToToolAndArguments() {
        val gate = ApprovalGate()
        val receipt = gate.approve(ApprovalRequest("r1", "calendar.create", "{\"to\":\"a\"}"))
        // Same id, different tool -> rejected.
        assertFalse(gate.isAuthorized(receipt, "email.send", "{\"to\":\"a\"}"))
        // Same id + tool, different arguments -> rejected.
        assertFalse(gate.isAuthorized(receipt, "calendar.create", "{\"to\":\"b\"}"))
        // Exact match -> authorized.
        assertTrue(gate.isAuthorized(receipt, "calendar.create", "{\"to\":\"a\"}"))
    }

    @Test
    fun expiredReceiptIsRejected() {
        var now = 0L
        val gate = ApprovalGate(approvalTtlMs = 1_000L, clock = { now })
        val receipt = gate.approve(ApprovalRequest("r1", "tool", "args"))
        now = 1_001L
        assertFalse(gate.isAuthorized(receipt, "tool", "args"))
    }

    @Test
    fun rejectRemovesGrant() {
        val gate = ApprovalGate()
        val request = ApprovalRequest("r1", "tool", "args")
        val receipt = gate.approve(request)
        gate.reject(request)
        assertFalse(gate.isAuthorized(receipt, "tool", "args"))
    }

    @Test
    fun unknownReceiptIsRejected() {
        val gate = ApprovalGate()
        val receipt = ApprovalReceipt("unknown", 0L, Long.MAX_VALUE)
        assertFalse(gate.isAuthorized(receipt, "tool", "args"))
    }

    @Test
    fun unconsumedReceiptWithinTtlIsAuthorized() {
        var now = 0L
        val gate = ApprovalGate(approvalTtlMs = 10_000L, clock = { now })
        val receipt = gate.approve(ApprovalRequest("r1", "tool", "args"))
        now = 5_000L
        assertTrue(gate.isAuthorized(receipt, "tool", "args"))
    }
}
