package com.nexus.agent.data.repository

/**
 * Best-effort mirror of conversation turns to an external store (Neon). Implementations must
 * never throw into the chat flow — failures are logged/ignored by design.
 */
interface ConversationMirror {
    suspend fun mirror(sessionId: String, role: String, content: String)
}
