package com.nexus.agent.domain.model

/**
 * A single chat turn. This is the only real model in Phase 1A; no persistence,
 * no remote calls, no tool execution.
 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val id: Long = 0L
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}
