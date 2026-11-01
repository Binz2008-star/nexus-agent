"""Live backend↔SQL parity against Neon staging (data-agnostic). The "JDBC" side is the direct
psycopg query using the same SQL the Android `SecondBrainSql` uses (byte-identical, proven by
`test_sql_parity.py`), so HTTP==direct-SQL implies HTTP==JDBC."""
import psycopg
import pytest

from sql_queries import SEARCH_CHUNKS_SQL, SEARCH_MEMORY_SQL, ilike_pattern

pytestmark = pytest.mark.staging

TOL = 1e-6

# Queries chosen to hit real Neon content (Arabic + English + code), not disposable fixtures.
QUERIES = ["user", "select", "function", "ال"]


def _reference_search(read_url, query, top_k):
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
    for query in QUERIES:
        r = client.post("/v1/brain/search", json={"query": query, "top_k": 10}, headers=auth)
        assert r.status_code == 200, f"search {query!r} failed: {r.status_code} {r.text[:200]}"
        http = r.json()["results"]
        ref = _reference_search(read_url, query, 10)
        assert len(http) == len(ref), f"cardinality mismatch for {query!r}: HTTP={len(http)} ref={len(ref)}"
        for h, re in zip(_canonical(http), _canonical(ref)):
            assert h["project"] == re["project"]
            assert h["file"] == re["file"]
            assert h["content"] == re["content"]
            assert h.get("chunk_type") == re.get("chunk_type")
            assert abs(h["score"] - re["score"]) < TOL


def test_search_returns_results_for_known_content(client, auth):
    # The Second Brain holds English code/content; "function" must return non-empty ranked hits.
    r = client.post("/v1/brain/search", json={"query": "function", "top_k": 5}, headers=auth)
    assert r.status_code == 200
    results = r.json()["results"]
    assert results, "expected non-empty results for 'function'"
    scores = [x["score"] for x in results]
    assert scores == sorted(scores, reverse=True)


def test_memory_save_and_recent_roundtrip(client, auth, write_url):
    marker = "staging-parity-memory"
    r = client.post("/v1/memory/save", json={"type": "parity_test", "content": marker, "tags": ["staging", "parity"]}, headers=auth)
    assert r.status_code == 200
    try:
        r2 = client.get("/v1/memory/recent", params={"limit": 100}, headers=auth)
        assert r2.status_code == 200
        assert any(i["type"] == "parity_test" and i["content"] == marker for i in r2.json()["items"])
        # Tags must round-trip as a text[] column value.
        with psycopg.connect(write_url) as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT tags FROM nexus_memory WHERE content = %s", (marker,))
                row = cur.fetchone()
        assert row is not None and row[0] == ["staging", "parity"]
    finally:
        with psycopg.connect(write_url) as conn:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM nexus_memory WHERE content = %s", (marker,))
            conn.commit()


def test_transcript_mirror_roundtrip(client, auth, admin_url):
    marker = "staging-parity-mirror"
    r = client.post("/v1/transcript/mirror", json={"session_id": marker, "role": "user", "content": "hello"}, headers=auth)
    assert r.status_code == 200
    try:
        # nexus can't SELECT nexus_conversations; verify via the owner-role URL.
        with psycopg.connect(admin_url) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT session_id, role, content FROM nexus_conversations WHERE session_id = %s",
                    (marker,),
                )
                rows = cur.fetchall()
        assert (marker, "user", "hello") in rows
    finally:
        with psycopg.connect(admin_url) as conn:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM nexus_conversations WHERE session_id = %s", (marker,))
            conn.commit()
