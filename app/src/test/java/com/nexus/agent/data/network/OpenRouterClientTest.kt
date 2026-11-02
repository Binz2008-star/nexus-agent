package com.nexus.agent.data.network

import com.nexus.agent.domain.model.ChatMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterClientTest {
    @Test fun sendsOnlyOpenrouterFreeWithUserKey() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"أهلاً"}}]}"""
            ))
            val client = OpenRouterClient(
                apiKeyProvider = { "sk-or-test" },
                baseUrl = server.url("/api/v1").toString()
            )
            val actual = client.complete(listOf(ChatMessage(ChatMessage.Role.USER, "مرحبا")), "deepseek-flash")
            assertEquals("أهلاً", actual.getOrThrow())
            val req = server.takeRequest()
            assertEquals("/api/v1/chat/completions", req.path)
            assertEquals("Bearer sk-or-test", req.getHeader("Authorization"))
            val json = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals(OpenRouterClient.FREE_MODEL, json.getValue("model").jsonPrimitive.content)
            assertFalse(json.containsKey("thinking")) // DeepSeek-specific config forbidden
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun preservesFunctionCallingSchema() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"test\"}"}}]}}]}"""
            ))
            val client = OpenRouterClient({ "sk-or-test" }, baseUrl = server.url("/api/v1").toString())
            val tools = listOf(Json.parseToJsonElement(
                """{"type":"function","function":{"name":"web_search","parameters":{"type":"object"}}}"""
            ).jsonObject)
            val response = client.completeWithTools(emptyList(), "deepseek-flash", tools).getOrThrow()
            assertTrue(response is CompletionResult.ToolCallRequested)
            assertEquals("web_search", (response as CompletionResult.ToolCallRequested).toolCalls.first().function.name)
            val json = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("auto", json.getValue("tool_choice").jsonPrimitive.content)
            assertTrue(json.containsKey("tools"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun streamsSseWithoutPaidModelSelection() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"one\"}}]}\n\n" +
                         "data: {\"choices\":[{\"delta\":{\"content\":\" two\"}}]}\n\n" +
                         "data: [DONE]\n\n"))
            val client = OpenRouterClient({ "sk-or-test" }, baseUrl = server.url("/api/v1").toString())
            val received = mutableListOf<String>()
            val output = client.completeStream(emptyList(), "deepseek-flash") { received.add(it) }
            assertEquals("one two", output.getOrThrow())
            assertEquals(listOf("one", " two"), received)
        } finally {
            server.shutdown()
        }
    }

    @Test fun unavailableKeyNeverCallsNetwork() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = OpenRouterClient({ null }, baseUrl = server.url("/api/v1").toString())
            assertTrue(client.complete(emptyList(), "deepseek-flash").isFailure)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun 429DoesNotRetry() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("limit"))
            val client = OpenRouterClient({ "sk-or-test" }, baseUrl = server.url("/api/v1").toString())
            assertTrue(client.complete(emptyList(), "deepseek-flash").isFailure)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
