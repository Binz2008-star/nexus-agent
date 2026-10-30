package com.nexus.agent.domain.tool

import kotlinx.serialization.json.JsonObject

/** A source/citation attached to a tool result (shown under the answer). */
data class ToolSource(
    val title: String,
    val project: String = "",
    val file: String = "",
    val snippet: String = "",
    val url: String? = null
)

/** Uniform result of a tool execution. */
sealed class ToolResult {
    data class Success(val content: String, val sources: List<ToolSource> = emptyList()) : ToolResult()
    data class Failure(val errorCode: String) : ToolResult()
    data class Blocked(val reason: String) : ToolResult()
}

/**
 * A single read-only tool. Future tools (file_search, web_search, calendar, …) all
 * implement this same interface. `input` is a JSON object so tools can evolve their
 * arguments without changing the orchestrator.
 */
interface Tool {
    val name: String
    suspend fun execute(input: JsonObject): ToolResult
}
