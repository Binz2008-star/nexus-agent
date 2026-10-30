package com.nexus.agent.domain.neon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondBrainSqlTest {

    @Test
    fun ilikePatternEscapesWildcards() {
        assertEquals("%hello%", SecondBrainSql.ilikePattern("hello"))
        assertEquals("%100\\%%", SecondBrainSql.ilikePattern("100%"))
        assertEquals("%a\\_b%", SecondBrainSql.ilikePattern("a_b"))
    }

    @Test
    fun tagsLiteralRendersPostgresArray() {
        assertEquals("{}", SecondBrainSql.tagsLiteral(emptyList()))
        assertEquals("{\"lang\"}", SecondBrainSql.tagsLiteral(listOf("lang")))
        assertEquals("{\"a\",\"b c\"}", SecondBrainSql.tagsLiteral(listOf("a", "b c")))
    }

    @Test
    fun mapChunkRowBuildsKnowledgeResult() {
        val r = SecondBrainSql.mapChunkRow(listOf("projA", "a.py", "function", "def f(): ...", 0.75))
        assertEquals("projA", r.project)
        assertEquals("a.py", r.file)
        assertEquals("function", r.chunk)
        assertEquals("def f(): ...", r.content)
        assertEquals(0.75, r.score, 1e-9)
    }

    @Test
    fun mapChunkRowHandlesNullsAndNonNumericScore() {
        val r = SecondBrainSql.mapChunkRow(listOf(null, null, null, null, "not-a-number"))
        assertEquals("", r.project)
        assertEquals("", r.file)
        assertEquals(null, r.chunk)
        assertEquals("", r.content)
        assertEquals(0.0, r.score, 1e-9)
    }

    @Test
    fun mapMemoryRowMarksProjectAsMemory() {
        val r = SecondBrainSql.mapMemoryRow(listOf("fact", "user prefers Arabic", 0.9))
        assertEquals("memory", r.project)
        assertEquals("fact", r.file)
        assertEquals("user prefers Arabic", r.content)
        assertEquals(0.9, r.score, 1e-9)
    }

    @Test
    fun errorCodeMapsKnownFailures() {
        assertEquals("no_connection", SecondBrainSql.errorCode(IllegalStateException("no connection")))
        assertEquals("auth", SecondBrainSql.errorCode(RuntimeException("password authentication failed")))
        assertEquals("network", SecondBrainSql.errorCode(RuntimeException("Connection refused")))
        assertEquals("not_found", SecondBrainSql.errorCode(RuntimeException("relation does not exist")))
        assertEquals("server", SecondBrainSql.errorCode(RuntimeException("something else")))
    }

    @Test
    fun sqlTemplatesUsePlaceholdersNotInlineValues() {
        assertTrue(SecondBrainSql.SEARCH_CHUNKS_SQL.contains("?"))
        assertTrue(SecondBrainSql.INSERT_CONVERSATION_SQL.contains("?"))
        assertTrue(!SecondBrainSql.SEARCH_CHUNKS_SQL.contains("ILIKE '%"))
    }
}
