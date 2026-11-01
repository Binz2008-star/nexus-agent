package com.nexus.agent.data.repository

import com.nexus.agent.data.remote.NeonJdbcClient
import com.nexus.agent.domain.model.KnowledgeResult
import com.nexus.agent.domain.neon.NeonMemoryItem
import com.nexus.agent.domain.neon.SecondBrainSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real-JDBC parity test: exercises the ACTUAL Android JDBC path (pgjdbc + [NeonJdbcClient] +
 * [SecondBrainSql]) against the disposable PostgreSQL (backend/docker-compose.test.yml).
 *
 * It asserts the same fixture facts the backend's `tests/integration/` assert, so a green run on
 * both sides proves JDBC == HTTP (same SQL, same helpers, same database ⇒ same rows).
 *
 * Runs only when NEON_READ_URL / NEON_WRITE_URL are set (the `jdbc-integration` CI job); the plain
 * `./gradlew test` run skips it via `assumeTrue`.
 */
class NeonJdbcIntegrationTest {

    private val readUrl: String = System.getenv("NEON_READ_URL") ?: ""
    private val writeUrl: String = System.getenv("NEON_WRITE_URL") ?: ""

    private val jdbc = NeonJdbcClient()

    private fun search(query: String, topK: Int): List<KnowledgeResult> {
        val pattern = SecondBrainSql.ilikePattern(query)
        val chunks = jdbc.query(
            readUrl, SecondBrainSql.SEARCH_CHUNKS_SQL,
            listOf(query, pattern, topK), SecondBrainSql::mapChunkRow
        )
        val memory = jdbc.query(
            readUrl, SecondBrainSql.SEARCH_MEMORY_SQL,
            listOf(query, pattern, topK), SecondBrainSql::mapMemoryRow
        )
        return (chunks + memory).sortedByDescending { it.score }.take(topK)
    }

    @Test
    fun searchMatchesFixtureWithNonIncreasingScores() {
        assumeTrue(readUrl.isNotBlank())
        val results = search("color", 10)
        assertTrue("expected a hit for 'color'", results.isNotEmpty())
        assertTrue("seeded 'blue' chunk should rank first", results[0].content.contains("blue", ignoreCase = true))
        val scores = results.map { it.score }
        assertEquals("scores must be non-increasing", scores.sortedDescending(), scores)
    }

    @Test
    fun memorySaveAndRecentRoundTrip() {
        assumeTrue(writeUrl.isNotBlank())
        val tags = SecondBrainSql.tagsLiteral(listOf("t1", "t2"))
        val inserted = jdbc.execute(
            writeUrl, SecondBrainSql.INSERT_MEMORY_SQL,
            listOf("fact", "jdbc-parity-unique", tags)
        )
        assertEquals(1, inserted)
        val items: List<NeonMemoryItem> = jdbc.query(
            writeUrl, SecondBrainSql.SELECT_MEMORY_SQL,
            listOf(100), SecondBrainSql::mapMemoryItemRow
        )
        assertTrue(items.any { it.type == "fact" && it.content == "jdbc-parity-unique" })
    }

    @Test
    fun transcriptMirrorInserts() {
        assumeTrue(writeUrl.isNotBlank())
        val inserted = jdbc.execute(
            writeUrl, SecondBrainSql.INSERT_CONVERSATION_SQL,
            listOf("sess-jdbc", "user", "hello")
        )
        assertEquals(1, inserted)
    }
}
