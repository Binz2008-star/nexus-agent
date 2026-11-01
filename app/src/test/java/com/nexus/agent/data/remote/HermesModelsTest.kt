package com.nexus.agent.data.remote

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes run status with output and runtime`() {
        val body = """
            {"object":"hermes.run","run_id":"r1","status":"completed","output":"hi",
             "usage":{"total_tokens":7},"runtime":{"provider":"openai","model":"gpt-5"}}
        """.trimIndent()
        val status = json.decodeFromString<HermesRunStatus>(body)
        assertEquals("r1", status.runId)
        assertEquals("completed", status.status)
        assertTrue(status.isTerminal)
        assertEquals("hi", status.output)
        assertEquals(7L, status.usage?.totalTokens)
        assertEquals("openai", status.runtime?.provider)
    }

    @Test
    fun `decodes capabilities and supportsRuns`() {
        val body = """
            {"object":"hermes.api_server.capabilities",
             "features":{"run_submission":true,"run_status":true,"run_events_sse":true,"run_approval":true}}
        """.trimIndent()
        val caps = json.decodeFromString<HermesCapabilities>(body)
        assertTrue(caps.supportsRuns)
        assertTrue(caps.features.runApproval)
    }

    @Test
    fun `run status with pending controls exposes identity`() {
        val body = """
            {"run_id":"r1","status":"waiting_for_approval",
             "pending_controls":[{"prompt_id":"p1","execution_generation":2,"tool":"memory_save"}]}
        """.trimIndent()
        val status = json.decodeFromString<HermesRunStatus>(body)
        assertFalse(status.isTerminal)
        assertEquals("p1", status.pendingControls.first().identity)
        assertEquals(2, status.pendingControls.first().executionGeneration)
    }

    @Test
    fun `health isOk is case-insensitive`() {
        assertTrue(json.decodeFromString<HermesHealth>("""{"status":"OK"}""").isOk)
        assertFalse(json.decodeFromString<HermesHealth>("""{"status":"degraded"}""").isOk)
    }
}
