package com.nexus.agent.domain.tool

import com.nexus.agent.domain.model.KnowledgeResult

/** Result of a knowledge search. Empty results are [Success] with an empty list. */
sealed class KnowledgeSearchResult {
    data class Success(val query: String, val results: List<KnowledgeResult>) : KnowledgeSearchResult()
    data class Failure(val errorCode: String) : KnowledgeSearchResult()
}

/**
 * Read-only search over the user's own knowledge base. No mutations, no task
 * execution, no shell — search only.
 */
interface KnowledgeTool {
    suspend fun search(query: String, topK: Int = 8): KnowledgeSearchResult
}
