package com.nexus.agent.domain.safety

import com.nexus.agent.domain.culture.CultureContext
import com.nexus.agent.domain.emotion.EmotionAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {

    private val culture = CultureContext()

    private fun policy(guard: UserBehaviorGuard = UserBehaviorGuard()) =
        SafetyPolicy(EmotionAnalyzer(), guard, IntentEvaluator(guard), culture, "BLOCKED")

    @Test
    fun benignMessageIsAllowed() = runBlocking {
        assertEquals(SafetyPolicy.Decision.Allow, policy().evaluate("s", "hi"))
        assertEquals(SafetyPolicy.Decision.Allow, policy().evaluate("s", "ما هو الطقس اليوم؟"))
    }

    @Test
    fun harmfulMessageIsRejected() = runBlocking {
        val decision = policy().evaluate("s", "I want to kill myself")
        assertTrue(decision is SafetyPolicy.Decision.Reject)
        assertTrue((decision as SafetyPolicy.Decision.Reject).reply.isNotEmpty())
    }

    @Test
    fun sensitiveTopicIsRejected() = runBlocking {
        assertTrue(policy().evaluate("s", "you are stupid") is SafetyPolicy.Decision.Reject)
        assertTrue(policy().evaluate("s", "what is your religion") is SafetyPolicy.Decision.Reject)
    }

    @Test
    fun blockedSessionRejectsEvenBenignMessages() = runBlocking {
        var now = 0L
        val guard = UserBehaviorGuard(blockThreshold = 2, clock = { now })
        val p = policy(guard)

        // Two harmful messages push the session past the block threshold.
        p.evaluate("s", "I want to kill myself")
        p.evaluate("s", "I want to kill myself")

        // Once blocked, even a benign message is rejected with the block message.
        val decision = p.evaluate("s", "hello")
        assertTrue(decision is SafetyPolicy.Decision.Reject)
        assertEquals("BLOCKED", (decision as SafetyPolicy.Decision.Reject).reply)
    }
}
