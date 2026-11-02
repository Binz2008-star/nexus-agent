"""Offline Android/backend wire-contract guard.

This test deliberately makes NO Neon or Cloud Run connections. It guards against
accidental loss of required JSON property names/endpoints in a future refactor.
It is a static source check, not a substitute for actual HTTPS E2E validation.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLIENT = ROOT / "app/src/main/java/com/nexus/agent/data/remote/NeonHttpClient.kt"
BACKEND = ROOT / "backend/main.py"

REQUIRED_JSON_FIELDS = {
    "results", "project", "file", "chunk_type", "content", "score",
    "query", "top_k", "items", "type", "tags",
    "session_id", "role", "detail",
}
REQUIRED_ENDPOINTS = {
    "POST": {"/v1/brain/search", "/v1/memory/save", "/v1/transcript/mirror"},
    "GET": {"/v1/memory/recent"},
}


def test_android_dtos_cover_required_backend_wire_keys():
    assert CLIENT.is_file(), f"Android client missing: {CLIENT}"
    source = CLIENT.read_text(encoding="utf-8")
    serial_names = set(re.findall(r'@SerialName\("([^"]+)"\)', source))
    direct_names = set(re.findall(r'\bval\s+([A-Za-z_]\w*)\s*:', source))
    missing = REQUIRED_JSON_FIELDS - (serial_names | direct_names)
    assert not missing, f"Android DTO wire keys missing: {sorted(missing)}"


def test_client_and_backend_agree_on_mutation_and_read_paths():
    assert BACKEND.is_file() and CLIENT.is_file()
    server = BACKEND.read_text(encoding="utf-8")
    android = CLIENT.read_text(encoding="utf-8")
    for method, urls in REQUIRED_ENDPOINTS.items():
        for url in urls:
            assert f'@app.{method.lower()}("{url}"' in server, (
                f"Backend route missing: {method} {url}"
            )
            assert url in android, f"Android route missing: {method} {url}"


def test_reader_endpoint_fails_closed_without_writer_credentials():
    source = BACKEND.read_text(encoding="utf-8")
    route = source.split('@app.get("/v1/memory/recent"', 1)[1].split(
        '@app.post("/v1/transcript/mirror"', 1
    )[0]
    assert 'if not MEMORY_READ_URL:' in route
    assert '_connect(MEMORY_READ_URL)' in route
    assert '_connect(WRITE_URL)' not in route
