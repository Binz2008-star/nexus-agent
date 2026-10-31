package com.nexus.agent.domain.tool

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaTest {

    @Test
    fun schemaHasFunctionEnvelope() {
        val schema = ToolSchema.forTool("web_search", "Search the web", ToolSchema.DEFAULT_QUERY_PARAMETERS)
        assertEquals("function", schema["type"]?.jsonPrimitive?.content)
        val fn = schema["function"]!!.jsonObject
        assertEquals("web_search", fn["name"]?.jsonPrimitive?.content)
        assertEquals("Search the web", fn["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun schemaDeclaresQueryParameter() {
        val schema = ToolSchema.forTool("web_search", "desc", ToolSchema.DEFAULT_QUERY_PARAMETERS)
        val params = schema["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals("object", params["type"]?.jsonPrimitive?.content)
        assertTrue(params["properties"]!!.jsonObject.containsKey("query"))
        assertTrue(params["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("query"))
    }

    @Test
    fun defaultQueryParametersAdvertiseQuery() {
        val params = ToolSchema.DEFAULT_QUERY_PARAMETERS
        assertTrue(params["properties"]!!.jsonObject.containsKey("query"))
        assertTrue(params["required"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("query"))
    }

    @Test
    fun memorySaveSchemaDeclaresContentNotQuery() {
        val tool = MemorySaveTool(save = { Result.success(Unit) })
        val params = tool.parameterSchema
        val properties = params["properties"]!!.jsonObject
        assertTrue(properties.containsKey("content"))
        assertFalse(properties.containsKey("query"))
        val required = params["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(required.contains("content"))
        assertFalse(required.contains("query"))
    }

    @Test
    fun orchestratorEmitsMemorySaveContentSchema() {
        val tool = MemorySaveTool(save = { Result.success(Unit) })
        val orch = ToolOrchestrator(mapOf("memory_save" to tool))
        val schema = orch.functionSchemas().single()
        val params = schema["function"]!!.jsonObject["parameters"]!!.jsonObject
        val properties = params["properties"]!!.jsonObject
        assertTrue(properties.containsKey("content"))
        assertFalse(properties.containsKey("query"))
    }
}
