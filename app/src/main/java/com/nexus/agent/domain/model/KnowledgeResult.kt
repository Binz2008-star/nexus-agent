package com.nexus.agent.domain.model

/**
 * A single search hit from the Second Brain knowledge base.
 * Content is an excerpt (backend truncates to ~1000 chars); it is never logged.
 */
data class KnowledgeResult(
    val project: String,
    val file: String,
    val chunk: String?,
    val content: String,
    val score: Double
)
