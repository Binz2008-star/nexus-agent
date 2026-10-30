package com.nexus.agent.domain.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorKnowledgeBaseTest {

    @Test
    fun matchesDatabaseConnectionError() {
        val solution = ErrorKnowledgeBase.matchError("Error: unable to connect to database")
        assertEquals("ERR_DB_CONNECTION_FAILED", solution?.code)
        assertEquals(ErrorCategory.DATABASE, solution?.category)
        assertEquals(true, solution?.autoRepairable)
        assertEquals("database_reconnect", solution?.repairStrategy)
    }

    @Test
    fun matchesApiTimeout() {
        val solution = ErrorKnowledgeBase.matchError("Request timed out after 30s")
        assertEquals("ERR_API_TIMEOUT", solution?.code)
        assertEquals("retry_with_backoff", solution?.repairStrategy)
    }

    @Test
    fun matchesRateLimitViaHttpCode() {
        val solution = ErrorKnowledgeBase.matchError("DeepSeek API error 429")
        assertEquals("ERR_API_RATE_LIMIT", solution?.code)
        assertEquals(false, solution?.autoRepairable)
    }

    @Test
    fun matchesAuthFailure() {
        val solution = ErrorKnowledgeBase.matchError("Invalid API key (401)")
        assertEquals("ERR_AUTH_FAILED", solution?.code)
        assertEquals(ErrorCategory.AUTH, solution?.category)
    }

    @Test
    fun unknownErrorReturnsNull() {
        assertNull(ErrorKnowledgeBase.matchError("some totally unrelated message"))
    }

    @Test
    fun categoryOfFallsBackToUncategorized() {
        assertEquals(ErrorCategory.UNCATEGORIZED, ErrorKnowledgeBase.categoryOf("blah blah"))
        assertEquals(ErrorCategory.API, ErrorKnowledgeBase.categoryOf("api timeout"))
    }

    @Test
    fun suggestedSolutionsAreNonEmpty() {
        val solution = ErrorKnowledgeBase.matchError("connection failed")!!
        assertTrue(solution.suggestedSolutions.isNotEmpty())
        assertTrue(solution.probableCauses.isNotEmpty())
    }
}
