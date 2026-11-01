"""Endpoint tests using FastAPI TestClient + a fake DB (no live Postgres required)."""
from fastapi.testclient import TestClient

import main

# Configure a known key for the tests (env vars are read at import time).
main.API_KEY = "test-secret"
main.READ_URL = "postgresql://reader@host/neondb"
main.WRITE_URL = "postgresql://writer@host/neondb"

client = TestClient(main.app)
AUTH = {"Authorization": "Bearer test-secret"}


class FakeCursor:
    def __init__(self, rowsets):
        self._rowsets = list(rowsets)
        self.executed = []

    def execute(self, sql, params):
        self.executed.append((sql, params))

    def fetchall(self):
        return self._rowsets.pop(0) if self._rowsets else []

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class FakeConn:
    def __init__(self, rowsets=()):
        self._rowsets = rowsets
        self.cursor_obj = None
        self.committed = False

    def cursor(self):
        self.cursor_obj = FakeCursor(self._rowsets)
        return self.cursor_obj

    def commit(self):
        self.committed = True

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def test_health_is_public():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_auth_required():
    assert client.get("/v1/memory/recent").status_code == 401
    assert client.get("/v1/memory/recent", headers={"Authorization": "Bearer wrong"}).status_code == 401


def test_brain_search_merges_and_ranks(monkeypatch):
    chunk = ("proj", "file.txt", "txt", "hello world", 0.9)
    mem = ("memory", "remember this", 0.5)
    conn = FakeConn(rowsets=[[chunk], [mem]])
    monkeypatch.setattr(main, "_connect", lambda url: conn)

    r = client.post("/v1/brain/search", json={"query": "hello", "top_k": 8}, headers=AUTH)
    assert r.status_code == 200
    results = r.json()["results"]
    assert len(results) == 2
    assert results[0]["content"] == "hello world"  # higher score first
    assert results[1]["project"] == "memory"


def test_brain_search_rejects_empty_and_long_query(monkeypatch):
    assert client.post("/v1/brain/search", json={"query": "  "}, headers=AUTH).status_code == 400
    assert client.post("/v1/brain/search", json={"query": "x" * 501}, headers=AUTH).status_code == 400


def test_memory_save_is_parameterized(monkeypatch):
    conn = FakeConn()
    monkeypatch.setattr(main, "_connect", lambda url: conn)
    r = client.post("/v1/memory/save", json={"type": "fact", "content": "hi", "tags": ["a", "b"]}, headers=AUTH)
    assert r.status_code == 200
    assert conn.committed
    sql, params = conn.cursor_obj.executed[0]
    assert "INSERT INTO nexus_memory" in sql
    assert params[0] == "fact" and params[1] == "hi"
    assert params[2] == '{"a","b"}'  # tags rendered as a text[] literal, passed as a parameter


def test_memory_save_rejects_empty_content(monkeypatch):
    r = client.post("/v1/memory/save", json={"type": "fact", "content": "  "}, headers=AUTH)
    assert r.status_code == 400


def test_memory_recent_returns_items(monkeypatch):
    conn = FakeConn(rowsets=[[("fact", "content 1"), ("pref", "content 2")]])
    monkeypatch.setattr(main, "_connect", lambda url: conn)
    r = client.get("/v1/memory/recent", params={"limit": 10}, headers=AUTH)
    assert r.status_code == 200
    assert r.json()["items"] == [{"type": "fact", "content": "content 1"}, {"type": "pref", "content": "content 2"}]


def test_transcript_mirror_inserts(monkeypatch):
    conn = FakeConn()
    monkeypatch.setattr(main, "_connect", lambda url: conn)
    r = client.post(
        "/v1/transcript/mirror",
        json={"session_id": "s1", "role": "user", "content": "hello"},
        headers=AUTH,
    )
    assert r.status_code == 200
    sql, params = conn.cursor_obj.executed[0]
    assert "INSERT INTO nexus_conversations" in sql
    assert params == ("s1", "user", "hello")


def test_db_error_maps_to_502(monkeypatch):
    import psycopg

    def failing(url):
        raise psycopg.OperationalError("password authentication failed for user")

    monkeypatch.setattr(main, "_connect", failing)
    r = client.post("/v1/brain/search", json={"query": "x"}, headers=AUTH)
    assert r.status_code == 502
    assert r.json()["detail"] == "auth"


def test_auth_fails_closed_when_no_key_configured():
    original = main.API_KEY
    main.API_KEY = ""
    try:
        assert client.get("/v1/memory/recent", headers=AUTH).status_code == 401
    finally:
        main.API_KEY = original


def test_memory_save_rejects_long_type():
    r = client.post("/v1/memory/save", json={"type": "x" * 51, "content": "hi"}, headers=AUTH)
    assert r.status_code == 400


def test_transcript_mirror_rejects_long_role():
    r = client.post("/v1/transcript/mirror", json={"session_id": "s", "role": "x" * 21, "content": "hi"}, headers=AUTH)
    assert r.status_code == 400

