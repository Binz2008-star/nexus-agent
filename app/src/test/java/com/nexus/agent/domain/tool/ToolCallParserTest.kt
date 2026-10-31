package com.nexus.agent.domain.tool

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun parsesValidCall() {
        val call = ToolCallParser.parse(
            name = "web_search",
            argumentsJson = """{"query":"weather in dubai"}"""
        )
        assertEquals("web_search", call?.name)
        assertEquals("weather in dubai", call?.arguments?.get("query"))
    }

    @Test
    fun rejectsBlankName() {
        assertNull(ToolCallParser.parse(name = "  ", argumentsJson = "{}"))
        assertNull(ToolCallParser.parse(name = null, argumentsJson = "{}"))
    }

    @Test
    fun toleratesMalformedArguments() {
        val call = ToolCallParser.parse(name = "web_search", argumentsJson = "not-json")
        assertEquals("web_search", call?.name)
        assertTrue(call?.arguments?.isEmpty() == true)
    }

    @Test
    fun toleratesMissingArguments() {
        val call = ToolCallParser.parse(name = "web_search", argumentsJson = null)
        assertEquals("web_search", call?.name)
        assertTrue(call?.arguments?.isEmpty() == true)
    }

    @Test
    fun roundTripsToJsonObject() {
        val call = ToolCall("web_search", mapOf("query" to "hi"))
        val obj = ToolCallParser.toJsonObject(call)
        assertEquals(JsonPrimitive("hi"), obj["query"])
    }
}
