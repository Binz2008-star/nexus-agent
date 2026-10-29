package com.nexus.agent.data.repository

import com.nexus.agent.domain.model.KnowledgeResult
import com.nexus.agent.domain.tool.KnowledgeSearchResult
import com.nexus.agent.domain.tool.KnowledgeTool
import kotlinx.serialization.SerializationException

/**
 * Implements [KnowledgeTool]. Validates the query, clamps topK, and maps failures to
 * stable error codes. Depends on a [searchApi] function so it is unit-testable without
 * a live HTTP client.
 */
class KnowledgeRepository(
    private val searchApi: suspend (query: String, topK: Int) -> Result<List<KnowledgeResult>>
) : KnowledgeTool {

    override suspend fun search(query: String, topK: Int): KnowledgeSearchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return KnowledgeSearchResult.Failure(ERROR_EMPTY)

        val clamped = topK.coerceIn(MIN_TOP_K, MAX_TOP_K)
        return searchApi(trimmed, clamped).fold(
            onSuccess = { KnowledgeSearchResult.Success(trimmed, it) },
            onFailure = { KnowledgeSearchResult.Failure(errorCode(it)) }
        )
    }

    private fun errorCode(e: Throwable): String = when {
        e.message?.contains("No knowledge base URL", ignoreCase = true) == true -> ERROR_NO_URL
        e.message?.contains("timeout", ignoreCase = true) == true ||
            e.message?.contains("timed out", ignoreCase = true) == true -> ERROR_TIMEOUT
        e.message?.contains("Unable to resolve host") == true ||
            e.message?.contains("Failed to connect") == true -> ERROR_NETWORK
        e is SerializationException -> ERROR_MALFORMED
        else -> ERROR_SERVER
    }

    companion object {
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 20

        const val ERROR_EMPTY = "empty"
        const val ERROR_NO_URL = "no_url"
        const val ERROR_NETWORK = "network"
        const val ERROR_TIMEOUT = "timeout"
        const val ERROR_SERVER = "server"
        const val ERROR_MALFORMED = "malformed"
    }
}
