package com.nexus.agent.domain.model

/**
 * The result of fetching and extracting a web page: its title, final (post-redirect) URL, and the
 * extracted readable text (bounded). Returned by the `web_fetch` tool.
 */
data class WebFetchPage(
    val title: String,
    val url: String,
    val text: String
)
