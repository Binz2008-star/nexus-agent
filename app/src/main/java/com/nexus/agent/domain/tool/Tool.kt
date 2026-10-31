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
 * Consequence level of a tool, enforced by the orchestrator. COMMIT tools must never run
 * without an explicit approval receipt (none are registered yet, so they are default-denied).
 */
enum class RiskLevel { READ, ANALYZE, PREPARE, COMMIT }

/**
 * A capability. [riskLevel] and [requiredScopes] form the minimal default-deny policy: every
 * tool declares what it may do and which permissions it needs, and the orchestrator refuses
 * anything beyond READ unless policy explicitly allows it. `input` is a JSON object so tools
 * can evolve their arguments without changing the orchestrator.
 */
interface Tool {
    val name: String

    /** Human/agent-facing description used in function-calling schemas. */
    val description: String get() = ""

    /** Consequence level. Defaults to READ — new tools are safe by default. */
    val riskLevel: RiskLevel get() = RiskLevel.READ

    /** OAuth/account scopes required to run (e.g. "gmail.readonly"). Empty = none. */
    val requiredScopes: List<String> get() = emptyList()

    suspend fun execute(input: JsonObject): ToolResult
}
