package com.nexus.agent.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HermesSseParserTest {

    @Test
    fun `parses event type and single data line`() {
        val parser = HermesSseParser()
        assertNull(parser.feed("event: run.completed"))
        assertNull(parser.feed("data: {\"output\":\"hi\"}"))
        val event = parser.feed("")
        assertEquals("run.completed", event?.type)
        assertEquals("{\"output\":\"hi\"}", event?.data)
    }

    @Test
    fun `joins multi-line data with newline`() {
        val parser = HermesSseParser()
        assertNull(parser.feed("data: first"))
        assertNull(parser.feed("data: second"))
        val event = parser.feed("")
        assertEquals("first\nsecond", event?.data)
    }

    @Test
    fun `ignores keepalive comments`() {
        val parser = HermesSseParser()
        assertNull(parser.feed(": keepalive"))
        assertNull(parser.feed(": another comment"))
        assertNull(parser.feed("event: message.delta"))
        assertNull(parser.feed("data: hi"))
        val event = parser.feed("")
        assertEquals("message.delta", event?.type)
        assertEquals("hi", event?.data)
    }

    @Test
    fun `ignores blank separators without data`() {
        val parser = HermesSseParser()
        assertNull(parser.feed(""))
        assertNull(parser.feed(""))
    }

    @Test
    fun `strips trailing carriage return`() {
        val parser = HermesSseParser()
        assertNull(parser.feed("event: run.failed\r"))
        assertNull(parser.feed("data: boom\r"))
        val event = parser.feed("\r")
        assertEquals("run.failed", event?.type)
        assertEquals("boom", event?.data)
    }

    @Test
    fun `resets state after dispatch`() {
        val parser = HermesSseParser()
        assertNull(parser.feed("event: run.completed"))
        assertNull(parser.feed("data: done"))
        assertEquals("run.completed", parser.feed("")?.type)

        // A fresh event without an explicit type defaults to "message".
        assertNull(parser.feed("data: next"))
        assertEquals("message", parser.feed("")?.type)
    }
    @Test
    fun `rejects oversized wire line before buffering`() {
        val parser = HermesSseParser()
        try {
            parser.feed("data: " + "x".repeat(HermesSseParser.MAX_LINE_CHARS))
            org.junit.Assert.fail("oversized line must fail")
        } catch (e: IllegalArgumentException) {
            assertEquals("hermes_sse_line_too_large", e.message)
        }
    }

    @Test
    fun `rejects oversized multiline event before accumulation`() {
        val parser = HermesSseParser()
        val line = "data: " + "x".repeat(4096)
        try {
            repeat(100) { parser.feed(line) }
            org.junit.Assert.fail("oversized SSE event must fail")
        } catch (e: IllegalArgumentException) {
            assertEquals("hermes_sse_event_too_large", e.message)
        }
    }

    @Test
    fun `many small events do not share the same memory budget`() {
        val parser = HermesSseParser()
        repeat(500) {
            assertNull(parser.feed("event: message.delta"))
            assertNull(parser.feed("data: ok"))
            assertEquals("ok", parser.feed("")?.data)
        }
    }

    @Test
    fun `rejects oversized event names`() {
        val parser = HermesSseParser()
        try {
            parser.feed("event: " + "x".repeat(200))
            org.junit.Assert.fail("oversized event name must fail")
        } catch (e: IllegalArgumentException) {
            assertEquals("hermes_sse_event_type_too_large", e.message)
        }
    }

}
