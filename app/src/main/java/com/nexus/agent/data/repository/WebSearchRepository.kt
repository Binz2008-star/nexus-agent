package com.nexus.agent.data.repository

import com.nexus.agent.domain.model.WebSearchResult
import com.nexus.agent.domain.tool.WebSearchOutcome
import com.nexus.agent.domain.tool.WebSearchTool
import kotlinx.serialization.SerializationException

/**
 * Implements [WebSearchTool]. Validates the query, clamps count, and maps failures to
 * stable error codes. Depends on a [searchApi] function so it is unit-testable without
 * a live HTTP client.
 */
class WebSearchRepository(
    private val searchApi: suspend (query: String, count: Int) -> Result<List<WebSearchResult>>
) : WebSearchTool {

    override suspend fun search(query: String, count: Int): WebSearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return WebSearchOutcome.Failure(ERROR_EMPTY)

        val clamped = count.coerceIn(MIN_COUNT, MAX_COUNT)
        return searchApi(trimmed, clamped).fold(
            onSuccess = { WebSearchOutcome.Success(trimmed, it) },
            onFailure = { WebSearchOutcome.Failure(errorCode(it)) }
        )
    }

    private fun errorCode(e: Throwable): String = when {
        e.message?.contains("No DeepSeek API key", ignoreCase = true) == true -> ERROR_NO_KEY
        e.message?.contains("401") == true -> ERROR_AUTH
        e.message?.contains("402") == true -> ERROR_QUOTA
        e.message?.contains("429") == true -> ERROR_RATE
        e.message?.contains("timeout", ignoreCase = true) == true ||
            e.message?.contains("timed out", ignoreCase = true) == true -> ERROR_TIMEOUT
        e.message?.contains("Unable to resolve host") == true ||
            e.message?.contains("Failed to connect") == true -> ERROR_NETWORK
        e is SerializationException -> ERROR_MALFORMED
        else -> ERROR_SERVER
    }

    companion object {
        const val MIN_COUNT = 1
        const val MAX_COUNT = 50

        const val ERROR_EMPTY = "empty"
        const val ERROR_NO_KEY = "no_key"
        const val ERROR_AUTH = "auth"
        const val ERROR_QUOTA = "quota"
        const val ERROR_RATE = "rate"
        const val ERROR_NETWORK = "network"
        const val ERROR_TIMEOUT = "timeout"
        const val ERROR_SERVER = "server"
        const val ERROR_MALFORMED = "malformed"
    }
}
