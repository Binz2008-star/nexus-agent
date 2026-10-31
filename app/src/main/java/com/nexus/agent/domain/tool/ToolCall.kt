package com.nexus.agent.domain.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A tool invocation proposed by the model (function-calling): a tool [name] plus string
 * arguments. The model may *propose* this; only the orchestrator (after policy + approval)
 * may *execute* it.
 */
data class ToolCall(
    val name: String,
    val arguments: Map<String, String> = emptyMap()
)

/**
 * Parses the model's function-call payload into a [ToolCall]. Pure + unit-testable so the
 * "model proposes → system validates" boundary is covered without a live model.
 */
object ToolCallParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses `name` + `arguments` (a JSON object serialized as a string) into a [ToolCall].
     * Returns null when the name is blank. Malformed arguments degrade to an empty map rather
     * than throwing — the orchestrator still has a valid tool to validate.
     */
    fun parse(name: String?, argumentsJson: String?): ToolCall? {
        val toolName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val args = argumentsJson?.let { raw ->
            try {
                json.parseToJsonElement(raw).jsonObject
                    .mapNotNull { (key, value) ->
                        (value as? JsonPrimitive)?.content?.let { key to it }
                    }
                    .toMap()
            } catch (e: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
        return ToolCall(toolName, args)
    }

    /** Serializes a [ToolCall]'s arguments to a [JsonObject] for the orchestrator. */
    fun toJsonObject(call: ToolCall): JsonObject =
        buildJsonObject { call.arguments.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
}
