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
}
