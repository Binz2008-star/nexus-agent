package com.nexus.agent.domain.tool

import com.nexus.agent.domain.model.GmailMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Result of a Gmail inbox search. Empty results are [Success] with an empty list. */
sealed class GmailSearchResult {
    data class Success(val query: String, val messages: List<GmailMessage>) : GmailSearchResult()
    data class Failure(val errorCode: String) : GmailSearchResult()
}

/**
 * Read-only search over the user's Gmail inbox. No send, delete, label, or draft — search only.
 */
interface GmailTool {
    suspend fun searchInbox(query: String, maxResults: Int = 10): GmailSearchResult
}

/**
 * Adapter exposing [GmailTool] through the generic [Tool] interface so it can be registered in
 * the [ToolOrchestrator]. Input: `{"query": "...", "max_results": "10"}`.
 */
class GmailSearchTool(private val gmail: GmailTool) : Tool {

    override val name: String = "gmail_search"

    override val description: String = "Search the user's Gmail inbox (read-only)."

    override val requiredScopes: List<String> get() = listOf(GMAIL_READONLY_SCOPE)

    override suspend fun execute(input: JsonObject): ToolResult {
        val query = (input["query"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.Failure("empty")
        val maxResults = (input["max_results"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.toIntOrNull() ?: 10

        return when (val r = gmail.searchInbox(query, maxResults)) {
            is GmailSearchResult.Success -> {
                val sources = r.messages.map { it.toToolSource() }
                val content = sources.joinToString("\n\n") { "${it.title}\n${it.snippet}" }
                ToolResult.Success(content, sources)
            }
            is GmailSearchResult.Failure -> ToolResult.Failure(r.errorCode)
        }
    }

    private fun GmailMessage.toToolSource() = ToolSource(
        title = subject.ifBlank { from.ifBlank { "Gmail message" } },
        snippet = snippet,
        url = null
    )
}

/** Gmail read-only scope required by [GmailSearchTool]. */
const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
