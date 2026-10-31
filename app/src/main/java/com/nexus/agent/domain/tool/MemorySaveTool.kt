package com.nexus.agent.domain.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Saves a fact/preference the user mentioned to long-term memory (Second Brain).
 *
 * This is the first COMMIT-level capability: it mutates persistent state, so it must never
 * run without an explicit approval receipt. The model may *propose* a save, but only the user
 * authorizes it through the approval card.
 *
 * Input JSON: `{"content": "the fact to remember"}`.
 */
class MemorySaveTool(
    private val save: suspend (content: String) -> Result<Unit>
) : Tool {

    override val name: String = "memory_save"

    override val description: String =
        "Save a fact or preference the user mentioned to long-term memory (requires approval). " +
            "Input JSON: {\"content\": \"the fact to remember\"}."

    override val riskLevel: RiskLevel = RiskLevel.COMMIT

    override val parameterSchema: JsonObject = ToolSchema.parameters(
        listOf(ToolSchema.Parameter("content", "The fact or preference to remember"))
    )

    override suspend fun execute(input: JsonObject): ToolResult {
        val content = (input["content"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (content.isEmpty()) return ToolResult.Failure("empty")

        return try {
            save(content).fold(
                onSuccess = { ToolResult.Success("Saved to memory: $content") },
                onFailure = { ToolResult.Failure("save_failed") }
            )
        } catch (e: Exception) {
            ToolResult.Failure("save_failed")
        }
    }
}
