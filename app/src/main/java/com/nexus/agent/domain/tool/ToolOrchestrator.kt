package com.nexus.agent.domain.tool

import kotlinx.serialization.json.JsonObject

/**
 * Lifecycle of a background agent task, observed by the UI. `Running.stage` is a
 * human-readable step ("searching", "thinking", …) for the "Working for Ns" chip.
 */
sealed interface TaskStatus {
    data object Idle : TaskStatus
    data class Running(val startedAtMs: Long, val stage: String) : TaskStatus
    data class Completed(val sources: List<ToolSource> = emptyList()) : TaskStatus
    data class Failed(val errorCode: String) : TaskStatus
}

/**
 * Runs named tools. Receives the user's request and delegates to the matching [Tool].
 * Tool *selection* is intentionally simple right now (the caller picks the tool); a
 * model-driven decision loop is a later step.
 */
class ToolOrchestrator(private val tools: Map<String, Tool>) {

    suspend fun execute(toolName: String, input: JsonObject): ToolResult {
        val tool = tools[toolName] ?: return ToolResult.Failure("unknown_tool")
        return tool.execute(input)
    }

    fun toolNames(): Set<String> = tools.keys
}

/**
 * Builds the system prompt that instructs the model to ground its answer in the given
 * tool excerpts (with citation guidance).
 */
fun buildContextPrompt(sources: List<ToolSource>): String {
    val body = sources.joinToString("\n\n") { source ->
        val origin = when {
            source.url != null -> " (${source.url})"
            source.project.isNotBlank() -> " [${source.project}]"
            else -> ""
        }
        "Source: ${source.title}$origin\n${source.snippet}"
    }
    return "Use the following excerpts to answer the user's question. Cite sources " +
        "when relevant. If the excerpts do not answer the question, say so.\n\n$body"
}
