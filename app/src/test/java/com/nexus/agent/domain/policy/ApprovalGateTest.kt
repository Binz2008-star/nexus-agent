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
        assertTrue(gate.isAuthorized(receipt))
        gate.consume(receipt)
        // Replay must be rejected.
        assertFalse(gate.isAuthorized(receipt))
    }

    @Test
    fun expiredReceiptIsRejected() {
        var now = 0L
        val gate = ApprovalGate(approvalTtlMs = 1_000L, clock = { now })
        val receipt = gate.approve(ApprovalRequest("r1", "tool", "args"))
        now = 1_001L
        assertFalse(gate.isAuthorized(receipt))
    }

    @Test
    fun rejectRemovesGrant() {
        val gate = ApprovalGate()
        val request = ApprovalRequest("r1", "tool", "args")
        val receipt = gate.approve(request)
        gate.reject(request)
        assertFalse(gate.isAuthorized(receipt))
    }

    @Test
    fun unknownReceiptIsRejected() {
        val gate = ApprovalGate()
        val receipt = ApprovalReceipt("unknown", 0L, Long.MAX_VALUE)
        assertFalse(gate.isAuthorized(receipt))
    }

    @Test
    fun unconsumedReceiptWithinTtlIsAuthorized() {
        var now = 0L
        val gate = ApprovalGate(approvalTtlMs = 10_000L, clock = { now })
        val receipt = gate.approve(ApprovalRequest("r1", "tool", "args"))
        now = 5_000L
        assertTrue(gate.isAuthorized(receipt))
    }
}
