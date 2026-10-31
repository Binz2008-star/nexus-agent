package com.nexus.agent.data.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun requestWithToolsSerializesToolSchema() {
        val tool = FunctionTool(function = FunctionDef("web_search", "Search the web"))
        val request = ChatCompletionRequest(
            model = "deepseek-flash",
            messages = listOf(ApiMessage("user", "hi")),
            tools = listOf(tool),
            toolChoice = "auto"
        )
        val s = json.encodeToString(request)
        assertTrue(s.contains("\"tools\""))
        assertTrue(s.contains("\"tool_choice\":\"auto\""))
        assertTrue(s.contains("\"web_search\""))
    }

    @Test
    fun responseWithToolCallDeserializes() {
        val body =
            """{"choices":[{"message":{"role":"assistant","content":null,""" +
                """"tool_calls":[{"id":"call_1","type":"function","function":{"name":"web_search",""" +
                """"arguments":"{\"query\":\"dubai weather\"}"}}]}}]}"""
        val parsed = json.decodeFromString<ChatCompletionResponse>(body)
        val call = parsed.choices.first().message?.toolCalls?.first()
        assertEquals("web_search", call?.function?.name)
        assertEquals("""{"query":"dubai weather"}""", call?.function?.arguments)
    }

    @Test
    fun plainTextResponseStillDeserializes() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"hello there"}}]}"""
        val parsed = json.decodeFromString<ChatCompletionResponse>(body)
        assertEquals("hello there", parsed.choices.first().message?.content)
    }
}
