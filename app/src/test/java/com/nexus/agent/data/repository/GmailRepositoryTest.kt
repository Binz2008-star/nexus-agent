package com.nexus.agent.data.repository

import com.nexus.agent.domain.model.GmailMessage
import com.nexus.agent.domain.tool.GmailSearchResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailRepositoryTest {

    @Test
    fun emptyQueryFailsWithEmpty() = runTest {
        val repo = GmailRepository { _, _ -> Result.success(emptyList()) }
        val result = repo.searchInbox("   ", 10)
        assertTrue(result is GmailSearchResult.Failure)
        assertEquals("empty", (result as GmailSearchResult.Failure).errorCode)
    }

    @Test
    fun maxResultsIsClamped() = runTest {
        var seen = 0
        val repo = GmailRepository { _, n -> seen = n; Result.success(emptyList()) }
        repo.searchInbox("invoice", 999)
        assertEquals(GmailRepository.MAX_RESULTS, seen)
    }

    @Test
    fun successMapsMessages() = runTest {
        val repo = GmailRepository { q, _ ->
            Result.success(listOf(GmailMessage("1", "t1", "Invoice", "boss@x.com", "snippet")))
        }
        val result = repo.searchInbox("invoice", 5)
        val success = result as GmailSearchResult.Success
        assertEquals("invoice", success.query)
        assertEquals(1, success.messages.size)
        assertEquals("Invoice", success.messages[0].subject)
    }

    @Test
    fun noTokenErrorIsMapped() = runTest {
        val repo = GmailRepository { _, _ ->
            Result.failure(IllegalStateException("No Google Gmail access token configured"))
        }
        val result = repo.searchInbox("x", 5)
        assertEquals("no_token", (result as GmailSearchResult.Failure).errorCode)
    }
}
