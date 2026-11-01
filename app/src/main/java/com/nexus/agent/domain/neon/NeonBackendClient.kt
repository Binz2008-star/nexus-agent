package com.nexus.agent.domain.neon

import com.nexus.agent.domain.tool.KnowledgeSearchResult

/**
 * Backend-facing Neon data access (the HTTPS replacement for direct JDBC). One implementation
 * ([com.nexus.agent.data.remote.NeonHttpClient]) talks to `backend/main.py`; the contract mirrors
 * [com.nexus.agent.data.repository.NeonRepository]'s persistence methods so either transport can be
 * selected behind a feature flag with identical behavior.
 */
interface NeonBackendClient {
    suspend fun search(query: String, topK: Int): KnowledgeSearchResult
    suspend fun saveMemory(type: String, content: String, tags: List<String>): Result<Unit>
    suspend fun loadMemory(limit: Int): Result<List<NeonMemoryItem>>
    suspend fun mirror(sessionId: String, role: String, content: String): Result<Unit>
}
