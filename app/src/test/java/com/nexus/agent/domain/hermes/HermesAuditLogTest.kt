package com.nexus.agent.domain.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HermesAuditLogTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "hermes-audit-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `writes only whitelisted fields`() {
        val dir = tempDir()
        val log = HermesAuditLog(File(dir, "audit.log.jsonl"), clock = { 123L })
        log.append("create_run", "run-1", mapOf("tool" to "web_search", "arguments" to "SUPER-SECRET"))

        val line = File(dir, "audit.log.jsonl").readLines().single()
        assertTrue(line.contains("\"event\":\"create_run\""))
        assertTrue(line.contains("\"run_id\":\"run-1\""))
        assertTrue(line.contains("\"tool\":\"web_search\""))
        // Non-whitelisted keys and secret values must never be written.
        assertFalse(line.contains("SUPER-SECRET"))
        assertFalse(line.contains("arguments"))
    }

    @Test
    fun `applies redactor to values`() {
        val dir = tempDir()
        val log = HermesAuditLog(
            File(dir, "a.log"),
            clock = { 1L },
            redactor = { if (it == "sekret") "***" else it }
        )
        log.append("create_run", "sekret")
        val line = File(dir, "a.log").readLines().single()
        assertTrue(line.contains("***"))
        assertFalse(line.contains("sekret"))
    }

    @Test
    fun `never throws when file cannot be written`() {
        val log = HermesAuditLog(File("/nonexistent-root/xyz/audit.log"))
        log.append("create_run") // must not throw
    }

    @Test
    fun `appends one line per event`() {
        val dir = tempDir()
        val log = HermesAuditLog(File(dir, "b.log"), clock = { 0L })
        log.append("tool_started", "r", mapOf("tool" to "t"))
        log.append("tool_completed", "r", mapOf("tool" to "t"))
        assertEquals(2, File(dir, "b.log").readLines().size)
    }
}
