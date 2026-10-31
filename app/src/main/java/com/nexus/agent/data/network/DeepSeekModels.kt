package com.nexus.agent.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ---- Request (non-streaming) ----

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    @SerialName("thinking") val thinking: ThinkingConfig? = null,
    val stream: Boolean = false,
    val tools: List<JsonObject>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null
)

@Serializable
data class ThinkingConfig(
    val type: String // "enabled" | "disabled" — no default so it is always serialized
)

@Serializable
data class ApiMessage(
    val role: String,
    // Nullable: assistant messages that carry a tool call have no text content.
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null
)

// ---- Function-calling (response) ----

@Serializable
data class ToolCallDto(
    val id: String = "",
    val type: String = "function",
    val function: FunctionCallDto
)

@Serializable
data class FunctionCallDto(
    val name: String = "",
    val arguments: String = "" // JSON object serialized as a string
)

// ---- Response (non-streaming) ----

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList(),
    val model: String? = null
)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: ApiMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

// ---- Streaming (SSE chunks) ----

@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<StreamChoice> = emptyList()
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class Delta(
    val content: String? = null,
    val role: String? = null
)
