"""HTTP-vs-SQL parity: the backend's search/memory/mirror endpoints must return the same rows a
direct (JDBC-equivalent) query would, with deterministic ordering and a numeric tolerance on the
`similarity()` score."""
import psycopg
import pytest

from sql_queries import SEARCH_CHUNKS_SQL, SEARCH_MEMORY_SQL, ilike_pattern

pytestmark = pytest.mark.integration

TOL = 1e-6


def _reference_search(read_url, query, top_k):
    """Direct psycopg query mirroring NeonRepository.search (the JDBC path's SQL + merge)."""
    pattern = ilike_pattern(query)
    with psycopg.connect(read_url) as conn:
        with conn.cursor() as cur:
            cur.execute(SEARCH_CHUNKS_SQL, (query, pattern, top_k))
            chunks = [
                {"project": r[0], "file": r[1], "chunk_type": r[2], "content": r[3], "score": float(r[4])}
                for r in cur.fetchall()
            ]
            cur.execute(SEARCH_MEMORY_SQL, (query, pattern, top_k))
            memory = [
                {"project": "memory", "file": r[0], "chunk_type": None, "content": r[1], "score": float(r[2])}
                for r in cur.fetchall()
            ]
    merged = chunks + memory
    merged.sort(key=lambda x: (-x["score"], x["project"], x["file"], x["content"]))
    return merged[:top_k]


def _canonical(results):
    return sorted(results, key=lambda x: (-x["score"], x["project"], x["file"], x["content"]))


def test_search_matches_direct_sql(client, auth, read_url):
    query = "color"
    r = client.post("/v1/brain/search", json={"query": query, "top_k": 10}, headers=auth)
    assert r.status_code == 200
    http = r.json()["results"]
    ref = _reference_search(read_url, query, 10)

    assert len(http) == len(ref)
    for h, re in zip(_canonical(http), _canonical(ref)):
        assert h["project"] == re["project"]
        assert h["file"] == re["file"]
        assert h["content"] == re["content"]
        assert h.get("chunk_type") == re.get("chunk_type")
        assert abs(h["score"] - re["score"]) < TOL


def test_search_scores_are_non_increasing(client, auth):
    r = client.post("/v1/brain/search", json={"query": "user", "top_k": 10}, headers=auth)
    assert r.status_code == 200
    scores = [x["score"] for x in r.json()["results"]]
    assert scores == sorted(scores, reverse=True)


def test_search_ranks_seeded_hit_first(client, auth):
    r = client.post("/v1/brain/search", json={"query": "color", "top_k": 10}, headers=auth)
    assert r.status_code == 200
    results = r.json()["results"]
    assert results, "expected at least one hit for query 'color'"
    assert "blue" in results[0]["content"].lower()


def test_memory_save_and_recent_roundtrip(client, auth, write_url):
    r = client.post(
        "/v1/memory/save",
        json={"type": "fact", "content": "parity-test-unique", "tags": ["t1", "t2"]},
        headers=auth,
    )
    assert r.status_code == 200

    r2 = client.get("/v1/memory/recent", params={"limit": 100}, headers=auth)
    assert r2.status_code == 200
    items = r2.json()["items"]
    assert any(i["type"] == "fact" and i["content"] == "parity-test-unique" for i in items)

    # The tags must round-trip as a real text[] column value.
    with psycopg.connect(write_url) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT tags FROM nexus_memory WHERE content = %s", ("parity-test-unique",))
            row = cur.fetchone()
    assert row is not None and row[0] == ["t1", "t2"]


def test_transcript_mirror_roundtrip(client, auth, admin_write_url):
    r = client.post(
        "/v1/transcript/mirror",
        json={"session_id": "sess-parity", "role": "user", "content": "mirror-test"},
        headers=auth,
    )
    assert r.status_code == 200

    # Verify via a privileged verifier role: nexus_writer has INSERT (not SELECT) on
    # nexus_conversations by design. (Local env uses the postgres superuser; Neon uses a verifier.)
    with psycopg.connect(admin_write_url) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT session_id, role, content FROM nexus_conversations WHERE session_id = %s",
                ("sess-parity",),
            )
            rows = cur.fetchall()
    assert ("sess-parity", "user", "mirror-test") in rows
