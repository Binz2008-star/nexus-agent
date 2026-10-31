package com.nexus.agent.domain.tool

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaTest {

    @Test
    fun schemaHasFunctionEnvelope() {
        val schema = ToolSchema.forTool("web_search", "Search the web")
        assertEquals("function", schema["type"]?.jsonPrimitive?.content)
        val fn = schema["function"]!!.jsonObject
        assertEquals("web_search", fn["name"]?.jsonPrimitive?.content)
        assertEquals("Search the web", fn["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun schemaDeclaresQueryParameter() {
        val schema = ToolSchema.forTool("web_search", "desc")
        val params = schema["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals("object", params["type"]?.jsonPrimitive?.content)
        assertTrue(params["properties"]!!.jsonObject.containsKey("query"))
        assertTrue(params["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("query"))
    }
}
