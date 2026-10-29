package com.nexus.agent.domain.tool

import com.nexus.agent.domain.model.WebSearchResult

/** Result of a web search. Empty results are [Success] with an empty list. */
sealed class WebSearchOutcome {
    data class Success(val query: String, val results: List<WebSearchResult>) : WebSearchOutcome()
    data class Failure(val errorCode: String) : WebSearchOutcome()
}

/** Read-only web search (DeepSeek Search API). No mutations. */
interface WebSearchTool {
    suspend fun search(query: String, count: Int = 10): WebSearchOutcome
}
