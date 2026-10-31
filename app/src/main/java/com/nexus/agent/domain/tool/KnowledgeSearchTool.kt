package com.nexus.agent.domain.tool

import com.nexus.agent.domain.model.KnowledgeResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Adapter exposing the existing Second Brain [KnowledgeTool] through the generic
 * [Tool] interface. Input: `{"query": "...", "top_k": 5}`.
 */
class KnowledgeSearchTool(private val knowledgeTool: KnowledgeTool) : Tool {

    override val name: String = "second_brain_search"
    override val description: String = "Search the user's Second Brain knowledge base."

    override suspend fun execute(input: JsonObject): ToolResult {
        val query = (input["query"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?: return ToolResult.Failure("empty")
        val topK = (input["top_k"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.toIntOrNull()
            ?: 8

        return when (val r = knowledgeTool.search(query, topK)) {
            is KnowledgeSearchResult.Success -> {
                val sources = r.results.map { it.toToolSource() }
                val content = sources.joinToString("\n\n") { "${it.file}\n${it.snippet}" }
                ToolResult.Success(content, sources)
            }
            is KnowledgeSearchResult.Failure -> ToolResult.Failure(r.errorCode)
        }
    }

    private fun KnowledgeResult.toToolSource() = ToolSource(
        title = file.ifBlank { project },
        project = project,
        file = file,
        snippet = content,
        url = null
    )
}
