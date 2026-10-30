package com.nexus.agent.domain.neon

import com.nexus.agent.domain.model.KnowledgeResult

/** A remembered fact/preference stored in Neon (personal-ai-core `nexus_memory`). */
data class NeonMemoryItem(val type: String, val content: String)

/**
 * Pure SQL + row-mapping + error mapping for the Neon-backed Second Brain. No I/O here so the
 * query strings, parameter rendering, and mappers are all unit-testable without a database.
 *
 * Search uses trigram-ranked substring matching (`ILIKE` + `similarity`) because it is
 * language-agnostic — the Second Brain holds Arabic, English, and code chunks.
 */
object SecondBrainSql {

    const val SEARCH_CHUNKS_SQL =
        "SELECT project_id, file_path, chunk_type, content, similarity(content, ?) AS score " +
            "FROM chunks_v4 WHERE content ILIKE ? ORDER BY score DESC LIMIT ?"

    const val SEARCH_MEMORY_SQL =
        "SELECT type, content, similarity(content, ?) AS score " +
            "FROM memory WHERE content ILIKE ? ORDER BY score DESC LIMIT ?"

    const val INSERT_CONVERSATION_SQL =
        "INSERT INTO nexus_conversations (session_id, role, content) VALUES (?, ?, ?)"

    const val INSERT_MEMORY_SQL =
        "INSERT INTO nexus_memory (type, content, tags) VALUES (?, ?, ?::text[])"

    const val SELECT_MEMORY_SQL =
        "SELECT type, content FROM nexus_memory ORDER BY created_at DESC LIMIT ?"

    /** Renders an ILIKE pattern with `%`/`_`/`\` escaped so a user query can't inject wildcards. */
    fun ilikePattern(query: String): String =
        "%" + query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"

    /** Renders a tag list as a Postgres `text[]` literal, e.g. `{"a","b c"}`. */
    fun tagsLiteral(tags: List<String>): String =
        "{" + tags.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "}"

    /** Maps a `chunks_v4` search row → a knowledge hit. Column order fixed by [SEARCH_CHUNKS_SQL]. */
    fun mapChunkRow(row: List<Any?>): KnowledgeResult = KnowledgeResult(
        project = row.getOrNull(0) as? String ?: "",
        file = row.getOrNull(1) as? String ?: "",
        chunk = row.getOrNull(2) as? String,
        content = row.getOrNull(3) as? String ?: "",
        score = (row.getOrNull(4) as? Number)?.toDouble() ?: 0.0
    )

    /** Maps a `memory` search row → a knowledge hit. Column order fixed by [SEARCH_MEMORY_SQL]. */
    fun mapMemoryRow(row: List<Any?>): KnowledgeResult = KnowledgeResult(
        project = "memory",
        file = row.getOrNull(0) as? String ?: "",
        chunk = null,
        content = row.getOrNull(1) as? String ?: "",
        score = (row.getOrNull(2) as? Number)?.toDouble() ?: 0.0
    )

    /** Maps a `nexus_memory` row → [NeonMemoryItem]. Column order fixed by [SELECT_MEMORY_SQL]. */
    fun mapMemoryItemRow(row: List<Any?>): NeonMemoryItem = NeonMemoryItem(
        type = row.getOrNull(0) as? String ?: "",
        content = row.getOrNull(1) as? String ?: ""
    )

    fun errorCode(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("driver", ignoreCase = true) ||
                msg.contains("initialize class", ignoreCase = true) ||
                msg.contains("failed resolution", ignoreCase = true) -> "driver"
            msg.contains("no connection", ignoreCase = true) ||
                msg.contains("no url", ignoreCase = true) -> "no_connection"
            msg.contains("password authentication failed", ignoreCase = true) ||
                msg.contains("28P01") -> "auth"
            msg.contains("does not exist", ignoreCase = true) -> "not_found"
            msg.contains("refused", ignoreCase = true) ||
                msg.contains("timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ||
                msg.contains("resolve host", ignoreCase = true) -> "network"
            else -> "server"
        }
    }
}
