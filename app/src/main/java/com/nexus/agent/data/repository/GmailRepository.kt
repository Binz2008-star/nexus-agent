package com.nexus.agent.data.repository

import com.nexus.agent.domain.model.GmailMessage
import com.nexus.agent.domain.tool.GmailSearchResult
import com.nexus.agent.domain.tool.GmailTool
import kotlinx.serialization.SerializationException

/**
 * Implements [GmailTool]. Validates the query, clamps maxResults, and maps failures to stable
 * error codes. Depends on a [searchApi] function so it is unit-testable without a live client.
 */
class GmailRepository(
    private val searchApi: suspend (query: String, maxResults: Int) -> Result<List<GmailMessage>>
) : GmailTool {

    override suspend fun searchInbox(query: String, maxResults: Int): GmailSearchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return GmailSearchResult.Failure(ERROR_EMPTY)
        val clamped = maxResults.coerceIn(MIN_RESULTS, MAX_RESULTS)
        return searchApi(trimmed, clamped).fold(
            onSuccess = { GmailSearchResult.Success(trimmed, it) },
            onFailure = { GmailSearchResult.Failure(errorCode(it)) }
        )
    }

    private fun errorCode(e: Throwable): String = when {
        e.message?.contains("No Google Gmail access token", ignoreCase = true) == true -> ERROR_NO_TOKEN
        e.message?.contains("401") == true -> ERROR_AUTH
        e.message?.contains("403") == true -> ERROR_FORBIDDEN
        e.message?.contains("429") == true -> ERROR_RATE
        e.message?.contains("timeout", ignoreCase = true) == true ||
            e.message?.contains("timed out", ignoreCase = true) == true -> ERROR_TIMEOUT
        e.message?.contains("Unable to resolve host") == true ||
            e.message?.contains("Failed to connect") == true -> ERROR_NETWORK
        e is SerializationException -> ERROR_MALFORMED
        else -> ERROR_SERVER
    }

    companion object {
        const val MIN_RESULTS = 1
        const val MAX_RESULTS = 50

        const val ERROR_EMPTY = "empty"
        const val ERROR_NO_TOKEN = "no_token"
        const val ERROR_AUTH = "auth"
        const val ERROR_FORBIDDEN = "forbidden"
        const val ERROR_RATE = "rate"
        const val ERROR_NETWORK = "network"
        const val ERROR_TIMEOUT = "timeout"
        const val ERROR_SERVER = "server"
        const val ERROR_MALFORMED = "malformed"
    }
}
