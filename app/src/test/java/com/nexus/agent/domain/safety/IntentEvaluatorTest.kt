package com.nexus.agent.domain.safety

import com.nexus.agent.domain.culture.CultureContext
import com.nexus.agent.domain.culture.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentEvaluatorTest {

    private class FakeClock {
        var nowMillis = 0L
    }

    private val context = CultureContext(region = Region.EGYPT)

    @Test
    fun firstViolationIsNotBlocked() {
        val guard = UserBehaviorGuard(clock = { FakeClock().nowMillis })
        val evaluator = IntentEvaluator(guard)
        val result = evaluator.handleSensitiveContent("s1", NeutralCategory.PROVOCATION, context)
        assertFalse(result.blocked)
        assertEquals(0, result.warningCount)
        assertTrue(result.reply.isNotBlank())
    }

    @Test
    fun repeatedViolationsEscalateToBlock() {
        val guard = UserBehaviorGuard(
            warningThreshold = 2, blockThreshold = 4,
            clock = { FakeClock().nowMillis }
        )
        val evaluator = IntentEvaluator(guard)
        repeat(3) { evaluator.handleSensitiveContent("s1", NeutralCategory.PROVOCATION, context) }
        val fourth = evaluator.handleSensitiveContent("s1", NeutralCategory.PROVOCATION, context)
        assertTrue(fourth.blocked)
        assertTrue(fourth.remainingBlockTimeMillis > 0)
    }

    @Test
    fun replyIsCulturallyAdapted() {
        val guard = UserBehaviorGuard(clock = { FakeClock().nowMillis })
        val evaluator = IntentEvaluator(guard)
        val result = evaluator.handleSensitiveContent("s1", NeutralCategory.PERSONAL_QUESTION, context)
        // Egyptian SOFT reply is used for the default (soft) context.
        assertEquals(
            "أنا هنا عشان أساعدك مش عشان أتكلم عن نفسي، ياريت نركز على اللي يفيدك 💗",
            result.reply
        )
    }
}
