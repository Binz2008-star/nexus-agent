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

    /**
     * Builds a `{"type":"function","function":{...}}` schema for a tool. All current tools take
     * a single free-text `query` argument, so the parameter schema is uniform.
     */
    fun forTool(name: String, description: String): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Free-text search query")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
            })
        })
    }
}
