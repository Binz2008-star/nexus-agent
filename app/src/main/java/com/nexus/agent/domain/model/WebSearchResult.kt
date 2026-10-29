package com.nexus.agent.domain.model

/**
 * A single web search hit from DeepSeek's Search API.
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val summary: String,
    val siteName: String?,
    val date: String?,
    val authority: Int,
    val score: Double
)
