package com.nexus.agent.domain.connectors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOAuthSessionTest {

    private val session =
        PendingOAuthSession(state = "abc123", verifier = "pkce-verifier", createdAtMs = 1_000L)

    @Test
    fun matchesStateAcceptsExactMatch() {
        assertTrue(session.matchesState("abc123"))
    }

    @Test
    fun matchesStateRejectsMismatch() {
        assertFalse(session.matchesState("other"))
    }

    @Test
    fun matchesStateRejectsNull() {
        assertFalse(session.matchesState(null))
    }

    @Test
    fun notExpiredWithinTtl() {
        assertFalse(session.isExpired(nowMs = 1_000L + 9_999L, ttlMillis = 10_000L))
    }

    @Test
    fun expiredAfterTtl() {
        assertTrue(session.isExpired(nowMs = 1_000L + 10_001L, ttlMillis = 10_000L))
    }
}
