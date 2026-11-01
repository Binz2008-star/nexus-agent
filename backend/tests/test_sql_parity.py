"""Cross-language parity: the backend SQL strings must match the Android `SecondBrainSql.kt`
exactly, modulo the JDBC `?` vs psycopg `%s` placeholder character. The Kotlin source is read
directly (single source of truth), so a change on the Android side is caught here."""
import re
from pathlib import Path

import pytest

from sql_queries import (
    INSERT_CONVERSATION_SQL,
    INSERT_MEMORY_SQL,
    SEARCH_CHUNKS_SQL,
    SEARCH_MEMORY_SQL,
    SELECT_MEMORY_SQL,
)

# repo root / app/src/main/java/.../SecondBrainSql.kt
KOTLIN_PATH = (
    Path(__file__).resolve().parents[2]
    / "app/src/main/java/com/nexus/agent/domain/neon/SecondBrainSql.kt"
)

PYTHON_SQL = {
    "SEARCH_CHUNKS_SQL": SEARCH_CHUNKS_SQL,
    "SEARCH_MEMORY_SQL": SEARCH_MEMORY_SQL,
    "INSERT_CONVERSATION_SQL": INSERT_CONVERSATION_SQL,
    "INSERT_MEMORY_SQL": INSERT_MEMORY_SQL,
    "SELECT_MEMORY_SQL": SELECT_MEMORY_SQL,
}


def _kotlin_constants() -> dict[str, str]:
    """Extract `const val NAME = "…" + "…"` blocks from SecondBrainSql.kt."""
    text = KOTLIN_PATH.read_text(encoding="utf-8")
    result: dict[str, str] = {}
    for chunk in text.split("const val ")[1:]:
        name = chunk.split("=", 1)[0].strip()
        rhs = chunk.split("=", 1)[1].split("fun ")[0]
        strings = re.findall(r'"((?:[^"\\]|\\.)*)"', rhs)
        result[name] = "".join(strings)
    return result


def test_sql_matches_android_second_brain_sql():
    if not KOTLIN_PATH.exists():
        pytest.skip("SecondBrainSql.kt not present (backend-only checkout)")
    kotlin = _kotlin_constants()
    for name, py_sql in PYTHON_SQL.items():
        assert name in kotlin, f"missing Kotlin constant {name}"
        assert py_sql == kotlin[name].replace("?", "%s"), f"{name} drifted from SecondBrainSql.kt"
