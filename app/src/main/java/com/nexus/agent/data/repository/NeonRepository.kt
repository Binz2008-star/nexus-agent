package com.nexus.agent.data.repository

import com.nexus.agent.data.remote.NeonJdbc
import com.nexus.agent.domain.neon.NeonMemoryItem
import com.nexus.agent.domain.neon.SecondBrainSql
import com.nexus.agent.domain.tool.KnowledgeSearchResult
import com.nexus.agent.domain.tool.KnowledgeTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Neon (Postgres) access for NEXUS:
 *  - [search] — read-only Second Brain search (`chunks_v4` + `memory`) via the read role.
 *  - [saveConversationMessage] / [saveMemory] / [loadMemory] — persistence via the write role.
 *  - [mirror] — best-effort conversation mirror (never throws into the chat flow).
 *
 * The read and write URLs are supplied by suspend providers (encrypted at rest) and never
 * hardcoded. All JDBC I/O runs on [ioDispatcher]; the pure SQL/mapping lives in [SecondBrainSql].
 */
class NeonRepository(
    private val readUrlProvider: suspend () -> String?,
    private val writeUrlProvider: suspend () -> String?,
    private val jdbc: NeonJdbc,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : KnowledgeTool, ConversationMirror {

    override suspend fun search(query: String, topK: Int): KnowledgeSearchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return KnowledgeSearchResult.Failure(KnowledgeRepository.ERROR_EMPTY)
        val clamped = topK.coerceIn(KnowledgeRepository.MIN_TOP_K, KnowledgeRepository.MAX_TOP_K)
        return withContext(ioDispatcher) {
            val url = readUrl() ?: return@withContext KnowledgeSearchResult.Failure(ERROR_NO_CONNECTION)
            try {
                val pattern = SecondBrainSql.ilikePattern(trimmed)
                val chunks = jdbc.query(
                    url, SecondBrainSql.SEARCH_CHUNKS_SQL,
                    listOf(trimmed, pattern, clamped), SecondBrainSql::mapChunkRow
                )
                val memory = jdbc.query(
                    url, SecondBrainSql.SEARCH_MEMORY_SQL,
                    listOf(trimmed, pattern, clamped), SecondBrainSql::mapMemoryRow
                )
                val merged = (chunks + memory).sortedByDescending { it.score }.take(clamped)
                KnowledgeSearchResult.Success(trimmed, merged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                // Includes LinkageError (NoClassDefFoundError/NoSuchMethodError) thrown by
                // pgjdbc on Android where JNDI/JDBC-4.2 shims are missing. Degrade, don't crash.
                KnowledgeSearchResult.Failure(SecondBrainSql.errorCode(e))
            }
        }
    }

    suspend fun saveConversationMessage(sessionId: String, role: String, content: String): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                val url = writeUrl() ?: return@withContext Result.failure(notConfigured())
                jdbc.execute(url, SecondBrainSql.INSERT_CONVERSATION_SQL, listOf(sessionId, role, content))
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    suspend fun saveMemory(type: String, content: String, tags: List<String> = emptyList()): Result<Unit> =
        withContext(ioDispatcher) {
            try {
                val url = writeUrl() ?: return@withContext Result.failure(notConfigured())
                jdbc.execute(
                    url, SecondBrainSql.INSERT_MEMORY_SQL,
                    listOf(type, content, SecondBrainSql.tagsLiteral(tags))
                )
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    suspend fun loadMemory(limit: Int = 50): Result<List<NeonMemoryItem>> =
        withContext(ioDispatcher) {
            try {
                val url = writeUrl() ?: return@withContext Result.failure(notConfigured())
                val clamped = limit.coerceIn(1, 200)
                val rows = jdbc.query(url, SecondBrainSql.SELECT_MEMORY_SQL, listOf(clamped), SecondBrainSql::mapMemoryItemRow)
                Result.success(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /** Best-effort mirror — ignores every failure so it can never break the chat flow. */
    override suspend fun mirror(sessionId: String, role: String, content: String) {
        saveConversationMessage(sessionId, role, content)
    }

    private suspend fun readUrl(): String? = readUrlProvider()?.trim()?.takeIf { it.isNotEmpty() }
    private suspend fun writeUrl(): String? = writeUrlProvider()?.trim()?.takeIf { it.isNotEmpty() }
    private fun notConfigured(): Exception = IllegalStateException(ERROR_NO_CONNECTION)

    companion object {
        const val ERROR_NO_CONNECTION = "no_connection"
    }
}
