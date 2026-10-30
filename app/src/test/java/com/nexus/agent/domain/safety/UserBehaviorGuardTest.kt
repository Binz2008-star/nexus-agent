package com.nexus.agent.domain.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserBehaviorGuardTest {

    private class FakeClock {
        var nowMillis = 0L
        fun advance(millis: Long) {
            nowMillis += millis
        }
    }

    @Test
    fun allowedBelowWarningThreshold() {
        val clock = FakeClock()
        val guard = UserBehaviorGuard(clock = { clock.nowMillis })
        assertTrue(guard.registerBadIntent("s1") is UserBehaviorGuard.BehaviorDecision.Allowed)
    }

    @Test
    fun warnsAtWarningThreshold() {
        val clock = FakeClock()
        val guard = UserBehaviorGuard(warningThreshold = 2, blockThreshold = 4, clock = { clock.nowMillis })
        guard.registerBadIntent("s1")
        val second = guard.registerBadIntent("s1")
        assertTrue(second is UserBehaviorGuard.BehaviorDecision.Warned)
        assertEquals(2, (second as UserBehaviorGuard.BehaviorDecision.Warned).warningCount)
    }

    @Test
    fun blocksAtBlockThreshold() {
        val clock = FakeClock()
        val guard = UserBehaviorGuard(warningThreshold = 2, blockThreshold = 4, clock = { clock.nowMillis })
        repeat(3) { guard.registerBadIntent("s1") }
        val fourth = guard.registerBadIntent("s1")
        assertTrue(fourth is UserBehaviorGuard.BehaviorDecision.Blocked)
        assertTrue(guard.isUserBlocked("s1"))
    }

    @Test
    fun blockExpiresAfterDuration() {
        val clock = FakeClock()
        val guard = UserBehaviorGuard(
            warningThreshold = 2, blockThreshold = 4,
            blockDurationMillis = 1000L, clock = { clock.nowMillis }
        )
        repeat(4) { guard.registerBadIntent("s1") }
        assertTrue(guard.isUserBlocked("s1"))
        clock.advance(1001)
        assertTrue(!guard.isUserBlocked("s1"))
    }

    @Test
    fun violationsExpireAfterWindow() {
        val clock = FakeClock()
        val guard = UserBehaviorGuard(
            warningThreshold = 2, blockThreshold = 4,
            intentExpiryMillis = 1000L, clock = { clock.nowMillis }
        )
        guard.registerBadIntent("s1") // 1 violation
        clock.advance(1001) // expiry window passed
        val result = guard.registerBadIntent("s1") // should count as the first again
        assertTrue(result is UserBehaviorGuard.BehaviorDecision.Allowed)
    }

    @Test
    fun blockedUserGetsBlockedDecisionWithRemainingTime() {
        val clock = FakeClock()
        val guard = UserBehaviorGuard(
            warningThreshold = 2, blockThreshold = 4,
            blockDurationMillis = 10_000L, clock = { clock.nowMillis }
        )
        repeat(4) { guard.registerBadIntent("s1") }
        clock.advance(2000)
        val result = guard.registerBadIntent("s1")
        assertTrue(result is UserBehaviorGuard.BehaviorDecision.Blocked)
        assertTrue((result as UserBehaviorGuard.BehaviorDecision.Blocked).remainingMillis in 1..10_000)
    }

    @Test
    fun resetClearsBlockAndViolations() {
        val clock = FakeClock()
        val guard = UserBehaviorGuard(warningThreshold = 2, blockThreshold = 4, clock = { clock.nowMillis })
        repeat(4) { guard.registerBadIntent("s1") }
        guard.reset("s1")
        assertTrue(!guard.isUserBlocked("s1"))
        assertTrue(guard.registerBadIntent("s1") is UserBehaviorGuard.BehaviorDecision.Allowed)
    }
}
