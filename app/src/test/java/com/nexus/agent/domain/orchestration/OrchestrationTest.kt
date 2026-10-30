package com.nexus.agent.domain.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestrationTest {

    @Test
    fun priorityLevelsAreOrdered() {
        assertTrue(PriorityLevel.CRITICAL.level < PriorityLevel.HIGH.level)
        assertTrue(PriorityLevel.BACKGROUND.level > PriorityLevel.LOW.level)
    }

    @Test
    fun orchestrationEventFromId() {
        assertEquals(OrchestrationEvent.EMOTION_DETECTED, OrchestrationEvent.fromId("emotion_detected"))
        assertEquals(OrchestrationEvent.ERROR_OCCURRED, OrchestrationEvent.fromId("error_occurred"))
        assertNull(OrchestrationEvent.fromId("bogus"))
    }

    @Test
    fun executionContextDefaults() {
        val ctx = ExecutionContext()
        assertEquals("gulf", ctx.dialect)
        assertEquals("ar", ctx.language)
        assertEquals("api", ctx.source)
        assertTrue(ctx.emotionData.isEmpty())
    }

    @Test
    fun executionContextCarriesDialectAndEmotion() {
        val ctx = ExecutionContext(
            sessionId = "s1",
            dialect = "egypt",
            emotionData = mapOf("dominant" to "فرح")
        )
        assertEquals("egypt", ctx.dialect)
        assertEquals("فرح", ctx.emotionData["dominant"])
    }
}
