package com.nexus.agent.domain.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds OpenAI/DeepSeek function-calling schemas from a tool's capability metadata. Pure and
 * unit-testable — this is the contract that lets the model *propose* a tool while the
 * orchestrator still validates + authorizes execution.
 */
object ToolSchema {

    /** A string-typed function-calling parameter declaration. */
    data class Parameter(
        val name: String,
        val description: String,
        val required: Boolean = true
    )

    /**
     * Builds a JSON-Schema `parameters` object (string properties + a `required` list).
     * Every current tool takes string arguments, so properties are string-typed.
     */
    fun parameters(properties: List<Parameter>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            properties.forEach { p ->
                put(p.name, buildJsonObject {
                    put("type", "string")
                    put("description", p.description)
                })
            }
        })
        val requiredNames = properties.filter { it.required }.map { it.name }
        if (requiredNames.isNotEmpty()) {
            put("required", buildJsonArray { requiredNames.forEach { add(JsonPrimitive(it)) } })
        }
    }

    /** Wraps a tool's name + description + parameters into the `{"type":"function",…}` envelope. */
    fun forTool(name: String, description: String, parameters: JsonObject): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        })
    }

    /** Default search-tool parameters: a single required `query` string. */
    val DEFAULT_QUERY_PARAMETERS: JsonObject =
        parameters(listOf(Parameter("query", "Free-text search query")))
}
