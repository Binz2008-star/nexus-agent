package com.nexus.agent.domain.cognitive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitiveLoadManagerTest {

    private class FakeClock {
        var nowMillis = 0L
        fun advance(millis: Long) {
            nowMillis += millis
        }
    }

    @Test
    fun allowsUpToMaxThenThrottles() = runBlocking {
        val clock = FakeClock()
        val manager = CognitiveLoadManager(
            maxMessagesPerWindow = 3,
            windowMillis = 10_000,
            cooldownMillis = 4_000,
            clock = { clock.nowMillis }
        )
        repeat(3) {
            assertTrue(manager.tryAcquire() is CognitiveLoadDecision.Allowed)
        }
        val blocked = manager.tryAcquire()
        assertTrue(blocked is CognitiveLoadDecision.Throttled)
        assertEquals(
            CognitiveLoadManager.DEFAULT_THROTTLE_MESSAGE,
            (blocked as CognitiveLoadDecision.Throttled).message
        )
    }

    @Test
    fun windowExpiryAllowsAgain() = runBlocking {
        val clock = FakeClock()
        val manager = CognitiveLoadManager(
            maxMessagesPerWindow = 3,
            windowMillis = 10_000,
            cooldownMillis = 4_000,
            clock = { clock.nowMillis }
        )
        repeat(3) { manager.tryAcquire() }
        manager.tryAcquire() // throttles and starts the cooldown
        clock.advance(10_001) // past window + cooldown
        assertTrue(manager.tryAcquire() is CognitiveLoadDecision.Allowed)
    }

    @Test
    fun resetClearsThrottle() = runBlocking {
        val clock = FakeClock()
        val manager = CognitiveLoadManager(
            maxMessagesPerWindow = 1,
            windowMillis = 10_000,
            cooldownMillis = 60_000,
            clock = { clock.nowMillis }
        )
        assertTrue(manager.tryAcquire() is CognitiveLoadDecision.Allowed)
        assertTrue(manager.tryAcquire() is CognitiveLoadDecision.Throttled)
        manager.reset()
        assertTrue(manager.tryAcquire() is CognitiveLoadDecision.Allowed)
    }

    @Test
    fun cooldownPauseThenAllow() = runBlocking {
        val clock = FakeClock()
        val manager = CognitiveLoadManager(
            maxMessagesPerWindow = 2,
            windowMillis = 60_000,
            cooldownMillis = 4_000,
            clock = { clock.nowMillis }
        )
        repeat(2) { assertTrue(manager.tryAcquire() is CognitiveLoadDecision.Allowed) }
        val blocked = manager.tryAcquire()
        assertTrue(blocked is CognitiveLoadDecision.Throttled)
        assertEquals(4_000, (blocked as CognitiveLoadDecision.Throttled).retryAfterMillis)
        clock.advance(4_001) // just past the cooldown, well before the window
        assertTrue(manager.tryAcquire() is CognitiveLoadDecision.Allowed)
    }
}
