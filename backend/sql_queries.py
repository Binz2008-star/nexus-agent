"""Pure SQL strings + row/error helpers for the NEXUS Neon backend.

Kept dependency-free so the query strings, parameter rendering, and error mapping are
unit-testable without a database. Mirrors `SecondBrainSql.kt` exactly, so staging parity
tests can assert identical rows to the existing Android JDBC path.
"""

# ---- SQL (mirrors SecondBrainSql.kt) ----
SEARCH_CHUNKS_SQL = (
    "SELECT project_id, file_path, chunk_type, content, similarity(content, %s) AS score "
    "FROM chunks_v4 WHERE content ILIKE %s ORDER BY score DESC LIMIT %s"
)
SEARCH_MEMORY_SQL = (
    "SELECT type, content, similarity(content, %s) AS score "
    "FROM memory WHERE content ILIKE %s ORDER BY score DESC LIMIT %s"
)
INSERT_CONVERSATION_SQL = (
    "INSERT INTO nexus_conversations (session_id, role, content) VALUES (%s, %s, %s)"
)
INSERT_MEMORY_SQL = (
    "INSERT INTO nexus_memory (type, content, tags) VALUES (%s, %s, %s::text[])"
)
SELECT_MEMORY_SQL = "SELECT type, content FROM nexus_memory ORDER BY created_at DESC LIMIT %s"

# Bounds (mirror NeonRepository/KnowledgeRepository: MIN_TOP_K=1, MAX_TOP_K=20; loadMemory caps at 200).
MIN_TOP_K, MAX_TOP_K = 1, 20
RECALL_CAP = 200

# Input size caps (defense-in-depth against oversized payloads).
MAX_QUERY_LEN = 500
MAX_CONTENT_LEN = 20_000
MAX_TAG_LEN = 100
MAX_TAGS = 20
MAX_TYPE_LEN = 50
# Allowed memory `type` values (matches MemorySaveTool's "fact" and the seed data).
MEMORY_TYPES = frozenset({"fact", "pref"})
MAX_ROLE_LEN = 20
MAX_SESSION_ID_LEN = 100


def ilike_pattern(query: str) -> str:
    """Renders an ILIKE pattern with ``%``/``_``/``\\`` escaped (no wildcard injection)."""
    return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"


def tags_literal(tags: list[str]) -> str:
    """Renders a tag list as a Postgres ``text[]`` literal, e.g. ``{"a","b c"}``."""
    return "{" + ",".join('"' + t.replace('"', '""') + '"' for t in tags) + "}"


def error_code(e: BaseException) -> str:
    """Maps a database error to a stable, non-sensitive code (mirrors SecondBrainSql.errorCode).

    Prefers the SQLSTATE code when the exception exposes one (psycopg sets `.sqlstate`);
    otherwise falls back to the legacy substring match for non-DB errors (e.g. a socket
    "connection refused" OSError).
    """
    sqlstate = getattr(e, "sqlstate", None)
    if isinstance(sqlstate, str) and sqlstate:
        if sqlstate == "28P01":  # invalid_password
            return "auth"
        if sqlstate == "42501":  # insufficient_privilege
            return "permission"
        if sqlstate == "42P01":  # undefined_table
            return "not_found"
        if sqlstate.startswith("08"):  # connection_exception family
            return "network"
        return "server"
    msg = str(e)
    if "password authentication failed" in msg or "28P01" in msg:
        return "auth"
    if "permission denied" in msg or "42501" in msg:
        return "permission"
    if "does not exist" in msg:
        return "not_found"
    if "refused" in msg or "timeout" in msg or "resolve host" in msg:
        return "network"
    return "server"
