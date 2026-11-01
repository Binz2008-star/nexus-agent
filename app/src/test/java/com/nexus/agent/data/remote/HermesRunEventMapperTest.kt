package com.nexus.agent.data.remote

import com.nexus.agent.domain.hermes.HermesRunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRunEventMapperTest {

    @Test
    fun `maps message delta`() {
        val event = HermesRunEventMapper.map(SseEvent("message.delta", """{"text":"hel"}"""))
        assertEquals(HermesRunEvent.Delta("hel"), event)
    }

    @Test
    fun `maps tool started`() {
        val event = HermesRunEventMapper.map(SseEvent("tool.started", """{"tool":"web_search","preview":"q=hi"}"""))
        assertTrue(event is HermesRunEvent.ToolStarted)
        assertEquals("web_search", (event as HermesRunEvent.ToolStarted).tool)
        assertEquals("q=hi", event.preview)
    }

    @Test
    fun `maps tool completed with error flag`() {
        val event = HermesRunEventMapper.map(
            SseEvent("tool.completed", """{"tool":"terminal","duration":1.5,"error":true,"preview":"denied"}""")
        )
        assertTrue(event is HermesRunEvent.ToolCompleted)
        val completed = event as HermesRunEvent.ToolCompleted
        assertEquals("terminal", completed.tool)
        assertEquals(1.5, completed.durationSec ?: 0.0, 0.0001)
        assertTrue(completed.error)
        assertEquals("denied", completed.preview)
    }

    @Test
    fun `maps approval request`() {
        val event = HermesRunEventMapper.map(
            SseEvent("approval.request", """{"request_id":"p1","execution_generation":3,"tool":"memory_save"}""")
        )
        assertTrue(event is HermesRunEvent.ApprovalRequested)
        val approval = event as HermesRunEvent.ApprovalRequested
        assertEquals("p1", approval.requestId)
        assertEquals(3, approval.executionGeneration)
        assertEquals("memory_save", approval.tool)
    }

    @Test
    fun `maps run completed terminal event`() {
        val event = HermesRunEventMapper.map(SseEvent("run.completed", """{"output":"done!"}"""))
        assertEquals(HermesRunEvent.Completed("done!"), event)
        assertTrue(event.isTerminal)
    }

    @Test
    fun `maps unknown event to Unknown and is not terminal`() {
        val event = HermesRunEventMapper.map(SseEvent("subagent.start", """{}"""))
        assertTrue(event is HermesRunEvent.Unknown)
        assertFalse(event.isTerminal)
    }

    @Test
    fun `maps pending control from status`() {
        val control = HermesPendingControl(
            promptId = "prompt-1",
            executionGeneration = 7,
            tool = "memory_save",
            arguments = "{\"fact\":\"x\"}"
        )
        val event = HermesRunEventMapper.fromPendingControl(control)
        assertTrue(event is HermesRunEvent.ApprovalRequested)
        assertEquals("prompt-1", (event as HermesRunEvent.ApprovalRequested).requestId)
        assertEquals(7, event.executionGeneration)
    }

    @Test
    fun `malformed payload degrades gracefully`() {
        val event = HermesRunEventMapper.map(SseEvent("run.completed", "not-json"))
        // obj() swallows the parse error → empty JsonObject → empty output.
        assertEquals(HermesRunEvent.Completed(""), event)
    }
}
