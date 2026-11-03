package com.nexus.agent.data.network

import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FailoverChatCompletionClientTest {
    private class FakeClient(
        var response: Result<String> = Result.success("primary"),
        var toolResponse: Result<CompletionResult> = Result.success(CompletionResult.Answer("primary"))
    ) : ChatCompletionClient {
        var calls = 0
        var toolCalls = 0
        var streamCalls = 0
        var lastModel: String? = null
        var emitBeforeFailure = false

        override suspend fun complete(history: List<ChatMessage>, model: String): Result<String> {
            calls++
            lastModel = model
            return response
        }

        override suspend fun completeStream(
            history: List<ChatMessage>, model: String,
            onDelta: suspend (String) -> Unit
        ): Result<String> {
            streamCalls++
            lastModel = model
            if (emitBeforeFailure) onDelta("partial")
            return response
        }

        override suspend fun completeWithTools(
            history: List<ChatMessage>, model: String,
            tools: List<kotlinx.serialization.json.JsonObject>
        ): Result<CompletionResult> {
            toolCalls++
            lastModel = model
            return toolResponse
        }
    }

    @Test fun quotaFailureUsesOneFreeFallback() = runBlocking {
        val primary = FakeClient(response = Result.failure(IOException("DeepSeek API error 402")))
        val free = FakeClient(response = Result.success("free answer"))
        val router = FailoverChatCompletionClient(primary, free) { true }
        assertEquals("free answer", router.complete(emptyList(), "deepseek-flash").getOrThrow())
        assertEquals(1, primary.calls)
        assertEquals(1, free.calls)
        assertEquals(OpenRouterClient.FREE_MODEL, free.lastModel)
    }

    @Test fun missingDeepSeekKeyAllowsFreeOnly() = runBlocking {
        val primary = FakeClient(response = Result.failure(IllegalStateException("No DeepSeek API key configured")))
        val free = FakeClient(response = Result.success("available"))
        val router = FailoverChatCompletionClient(primary, free) { true }
        assertEquals("available", router.complete(emptyList(), "deepseek-flash").getOrThrow())
        assertEquals(1, free.calls)
    }

    @Test fun disabledFallbackDoesNotShareUserContext() = runBlocking {
        val primary = FakeClient(response = Result.failure(IOException("DeepSeek API error 402")))
        val free = FakeClient()
        val router = FailoverChatCompletionClient(primary, free) { false }
        assertTrue(router.complete(emptyList(), "deepseek-flash").isFailure)
        assertEquals(0, free.calls)
    }

    @Test fun primarySuccessNeverContactsFallback() = runBlocking {
        val primary = FakeClient()
        val free = FakeClient()
        val router = FailoverChatCompletionClient(primary, free) { true }
        assertEquals("primary", router.complete(emptyList(), "deepseek-flash").getOrThrow())
        assertEquals(0, free.calls)
    }

    @Test fun unknownOrAuthErrorsDoNotTriggerFallback() = runBlocking {
        for (reason in listOf("DeepSeek API error 401", "arbitrary failure")) {
            val primary = FakeClient(response = Result.failure(IOException(reason)))
            val free = FakeClient()
            val router = FailoverChatCompletionClient(primary, free) { true }
            assertTrue(router.complete(emptyList(), "deepseek-flash").isFailure)
            assertEquals(0, free.calls)
        }
    }

    @Test fun noFailoverAfterPartialStream() = runBlocking {
        val primary = FakeClient(response = Result.failure(IOException("DeepSeek API error 402")))
        primary.emitBeforeFailure = true
        val free = FakeClient(response = Result.success("fallback"))
        val router = FailoverChatCompletionClient(primary, free) { true }
        val chunks = mutableListOf<String>()
        assertTrue(router.completeStream(emptyList(), "deepseek-flash") { chunks.add(it) }.isFailure)
        assertEquals(listOf("partial"), chunks)
        assertEquals(0, free.streamCalls)
    }

    @Test fun toolProposalCanUseOpenRouterWithoutSkippingApprovalGate() = runBlocking {
        val primary = FakeClient(
            toolResponse = Result.failure(IOException("DeepSeek API error 402"))
        )
        val free = FakeClient(toolResponse = Result.success(CompletionResult.Answer("proposed")))
        val router = FailoverChatCompletionClient(primary, free) { true }
        val schemas = listOf(buildJsonObject { put("name", "gmail_search") })
        val actual = router.completeWithTools(emptyList(), "deepseek-flash", schemas)
        assertTrue(actual.isSuccess)
        assertEquals(1, primary.toolCalls)
        assertEquals(1, free.toolCalls)
        assertEquals(OpenRouterClient.FREE_MODEL, free.lastModel)
        // This layer returns proposals; authorization remains in ToolOrchestrator.
        assertTrue(actual.getOrThrow() is CompletionResult.Answer)
    }

    @Test fun primaryThrowingExceptionStillFallsOver() = runBlocking {
        val primary = object : ChatCompletionClient {
            override suspend fun complete(history: List<ChatMessage>, model: String): Result<String> {
                throw IOException("DeepSeek API error 503: service unavailable")
            }
            override suspend fun completeWithTools(
                history: List<ChatMessage>,
                model: String,
                tools: List<kotlinx.serialization.json.JsonObject>
            ): Result<CompletionResult> = Result.failure(IOException("unused"))
            override suspend fun completeStream(
                history: List<ChatMessage>,
                model: String,
                onDelta: suspend (String) -> Unit
            ): Result<String> = Result.failure(IOException("unused"))
        }
        val free = FakeClient(response = Result.success("free answer"))
        val router = FailoverChatCompletionClient(primary, free) { true }
        assertEquals("free answer", router.complete(emptyList(), "deepseek-flash").getOrThrow())
        assertEquals(1, free.calls)
    }

    @Test fun thrownNonWhitelistedErrorDoesNotFallOver() = runBlocking {
        val primary = object : ChatCompletionClient {
            override suspend fun complete(history: List<ChatMessage>, model: String): Result<String> {
                throw IOException("network down")
            }
            override suspend fun completeWithTools(
                history: List<ChatMessage>,
                model: String,
                tools: List<kotlinx.serialization.json.JsonObject>
            ): Result<CompletionResult> = Result.failure(IOException("unused"))
            override suspend fun completeStream(
                history: List<ChatMessage>,
                model: String,
                onDelta: suspend (String) -> Unit
            ): Result<String> = Result.failure(IOException("unused"))
        }
        val free = FakeClient()
        val router = FailoverChatCompletionClient(primary, free) { true }
        assertTrue(router.complete(emptyList(), "deepseek-flash").isFailure)
        assertEquals(0, free.calls)
    }

    @Test fun cancellationFromPrimaryPropagatesInsteadOfBecomingAFailure() = runBlocking {
        val primary = object : ChatCompletionClient {
            override suspend fun complete(history: List<ChatMessage>, model: String): Result<String> {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
            override suspend fun completeWithTools(
                history: List<ChatMessage>,
                model: String,
                tools: List<kotlinx.serialization.json.JsonObject>
            ): Result<CompletionResult> = Result.failure(IOException("unused"))
            override suspend fun completeStream(
                history: List<ChatMessage>,
                model: String,
                onDelta: suspend (String) -> Unit
            ): Result<String> = Result.failure(IOException("unused"))
        }
        val free = FakeClient(response = Result.success("free answer"))
        val router = FailoverChatCompletionClient(primary, free) { true }
        var propagated = false
        try {
            router.complete(emptyList(), "deepseek-flash")
        } catch (e: kotlinx.coroutines.CancellationException) {
            propagated = true
        }
        assertTrue("cancellation must not be swallowed into a failed Result", propagated)
        assertEquals(0, free.calls)
    }

    @Test fun cancellationReturnedAsFailureIsRethrown() = runBlocking {
        // Some clients catch CancellationException themselves and return it inside a Result.
        // Returning that as an ordinary failure would leave the coroutine running and show the
        // user an error after they pressed Stop.
        val primary = object : ChatCompletionClient {
            override suspend fun complete(history: List<ChatMessage>, model: String): Result<String> =
                Result.failure(kotlinx.coroutines.CancellationException("cancelled"))
            override suspend fun completeWithTools(
                history: List<ChatMessage>,
                model: String,
                tools: List<kotlinx.serialization.json.JsonObject>
            ): Result<CompletionResult> = Result.failure(IOException("unused"))
            override suspend fun completeStream(
                history: List<ChatMessage>,
                model: String,
                onDelta: suspend (String) -> Unit
            ): Result<String> = Result.failure(IOException("unused"))
        }
        val free = FakeClient(response = Result.success("free answer"))
        val router = FailoverChatCompletionClient(primary, free) { true }
        var propagated = false
        try {
            router.complete(emptyList(), "deepseek-flash")
        } catch (e: kotlinx.coroutines.CancellationException) {
            propagated = true
        }
        assertTrue("a Result-wrapped cancellation must be rethrown", propagated)
        assertEquals(0, free.calls)
    }

    @Test fun failoverMatchesErrorPrefixNotExactString() = runBlocking {
        for (reason in listOf(
            "DeepSeek API error 402: Insufficient credits",
            "DeepSeek API error 429: Too many requests",
            "DeepSeek API error 503: Service unavailable"
        )) {
            val primary = FakeClient(response = Result.failure(IOException(reason)))
            val free = FakeClient(response = Result.success("free answer"))
            val router = FailoverChatCompletionClient(primary, free) { true }
            assertEquals("free answer", router.complete(emptyList(), "deepseek-flash").getOrThrow())
            assertEquals(1, free.calls)
        }
    }

    @Test fun secondProviderFailureDoesNotCascadeIntoMoreRetries() = runBlocking {
        val primary = FakeClient(response = Result.failure(IOException("DeepSeek API error 402")))
        val free = FakeClient(response = Result.failure(IOException("OpenRouter API error 429")))
        val router = FailoverChatCompletionClient(primary, free) { true }
        assertFalse(router.complete(emptyList(), "deepseek-flash").isSuccess)
        assertEquals(1, primary.calls)
        assertEquals(1, free.calls)
    }
}
