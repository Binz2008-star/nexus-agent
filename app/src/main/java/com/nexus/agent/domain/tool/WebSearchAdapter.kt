package com.nexus.agent.domain.tool

import com.nexus.agent.domain.model.WebSearchResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Adapter exposing the existing DeepSeek [WebSearchTool] through the generic [Tool]
 * interface. Input: `{"query": "...", "count": 5}`.
 *
 * This is the default grounding tool for the agentic flow because it needs no manual
 * configuration beyond the DeepSeek API key (unlike the Second Brain knowledge search,
 * which requires a separately configured base URL).
 */
class WebSearchAdapter(private val webSearchTool: WebSearchTool) : Tool {

    override val name: String = "web_search"
    override val description: String = "Search the web for up-to-date information."

    override suspend fun execute(input: JsonObject): ToolResult {
        val query = (input["query"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?: return ToolResult.Failure("empty")
        val count = (input["count"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.toIntOrNull()
            ?: 5

        return when (val r = webSearchTool.search(query, count)) {
            is WebSearchOutcome.Success -> {
                val sources = r.results.map { it.toToolSource() }
                val content = sources.joinToString("\n\n") { "${it.title}\n${it.snippet}" }
                ToolResult.Success(content, sources)
            }
            is WebSearchOutcome.Failure -> ToolResult.Failure(r.errorCode)
        }
    }

    private fun WebSearchResult.toToolSource() = ToolSource(
        title = title,
        snippet = snippet.ifBlank { summary },
        url = url,
        project = siteName.orEmpty()
    )
}
