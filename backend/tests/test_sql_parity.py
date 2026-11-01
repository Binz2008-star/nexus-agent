"""Cross-language parity: the backend SQL strings must match the Android `SecondBrainSql.kt`
exactly, modulo the JDBC `?` vs psycopg `%s` placeholder character. This is the transitive link
that makes "HTTP == JDBC": same SQL text + same parameters + same Postgres ⇒ same rows."""

from sql_queries import (
    INSERT_CONVERSATION_SQL,
    INSERT_MEMORY_SQL,
    SEARCH_CHUNKS_SQL,
    SEARCH_MEMORY_SQL,
    SELECT_MEMORY_SQL,
)

# Exact constants from app/src/main/java/com/nexus/agent/domain/neon/SecondBrainSql.kt
KOTLIN_SEARCH_CHUNKS = (
    "SELECT project_id, file_path, chunk_type, content, similarity(content, ?) AS score "
    "FROM chunks_v4 WHERE content ILIKE ? ORDER BY score DESC LIMIT ?"
)
KOTLIN_SEARCH_MEMORY = (
    "SELECT type, content, similarity(content, ?) AS score "
    "FROM memory WHERE content ILIKE ? ORDER BY score DESC LIMIT ?"
)
KOTLIN_INSERT_CONVERSATION = "INSERT INTO nexus_conversations (session_id, role, content) VALUES (?, ?, ?)"
KOTLIN_INSERT_MEMORY = "INSERT INTO nexus_memory (type, content, tags) VALUES (?, ?, ?::text[])"
KOTLIN_SELECT_MEMORY = "SELECT type, content FROM nexus_memory ORDER BY created_at DESC LIMIT ?"


def _jdbc(sql: str) -> str:
    """psycopg `%s` → JDBC `?` for comparison with the Kotlin constants."""
    return sql.replace("%s", "?")


def test_sql_matches_android_second_brain_sql():
    assert _jdbc(SEARCH_CHUNKS_SQL) == KOTLIN_SEARCH_CHUNKS
    assert _jdbc(SEARCH_MEMORY_SQL) == KOTLIN_SEARCH_MEMORY
    assert _jdbc(INSERT_CONVERSATION_SQL) == KOTLIN_INSERT_CONVERSATION
    assert _jdbc(INSERT_MEMORY_SQL) == KOTLIN_INSERT_MEMORY
    assert _jdbc(SELECT_MEMORY_SQL) == KOTLIN_SELECT_MEMORY
