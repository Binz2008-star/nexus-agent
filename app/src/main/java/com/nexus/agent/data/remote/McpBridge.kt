package com.nexus.agent.data.remote

import com.nexus.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * REST bridge to the Second Brain backend (NOT an MCP runtime). Only the read-only
 * `search` tool is allowed; task-execution and mutation tools are explicitly blocked so
 * they can never be invoked even if the model tries.
 */
class McpBridge(
    private val search: suspend (JsonObject) -> ToolResult
) {
    suspend fun call(tool: String, input: JsonObject): ToolResult = when (tool) {
        "search", "second_brain_search" -> search(input)
        "agent_task", "apply_patch", "replace_block", "shell" ->
            ToolResult.Blocked("Blocked tool: $tool")
        else -> ToolResult.Failure("unknown_tool")
    }

    companion object {
        val BLOCKED_TOOLS = setOf("agent_task", "apply_patch", "replace_block", "shell")
    }
}
