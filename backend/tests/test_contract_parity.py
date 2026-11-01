"""Android↔backend API-contract parity: every field name the backend produces/accepts must be
present in the Android `NeonHttpClient` DTOs (single source of truth = the Kotlin file). Catches
silent DTO drift between the HTTPS client and the backend."""
import re
from pathlib import Path

import pytest

KOTLIN_PATH = (
    Path(__file__).resolve().parents[2]
    / "app/src/main/java/com/nexus/agent/data/remote/NeonHttpClient.kt"
)

# Wire keys the backend emits/accepts (main.py responses + request bodies). Keep in sync with
# main.py and the client DTOs; the test fails if a key disappears from the Android side.
BACKEND_KEYS = {
    # brain/search
    "results", "project", "file", "chunk_type", "content", "score", "query", "top_k",
    # memory/save + memory/recent
    "items", "type", "tags",
    # transcript/mirror
    "session_id", "role",
    # errors + acks
    "detail",
}


def _kotlin_dto_names() -> set[str]:
    if not KOTLIN_PATH.exists():
        pytest.skip("NeonHttpClient.kt not present (backend-only checkout)")
    text = KOTLIN_PATH.read_text(encoding="utf-8")
    names = set(re.findall(r'@SerialName\("([^"]+)"\)', text))
    names |= set(re.findall(r"val ([a-zA-Z_][a-zA-Z0-9_]*)\s*:", text))
    return names


def test_backend_keys_are_covered_by_android_dtos():
    names = _kotlin_dto_names()
    missing = BACKEND_KEYS - names
    assert not missing, f"backend wire keys missing from NeonHttpClient DTOs: {sorted(missing)}"
