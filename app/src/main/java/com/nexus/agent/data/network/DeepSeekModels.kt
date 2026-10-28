package com.nexus.agent.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    @SerialName("thinking") val thinking: ThinkingConfig? = null,
    val stream: Boolean = false
)

@Serializable
data class ThinkingConfig(
    val type: String = "disabled" // "enabled" | "disabled"
)

@Serializable
data class ApiMessage(
    val role: String,
    val content: String
)

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
