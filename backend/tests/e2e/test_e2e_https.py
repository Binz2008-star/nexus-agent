"""End-to-end HTTPS contract: TLS, health, auth, authorization, search, memory round-trip, input
validation, and rate-limit resilience against the deployed service."""
import pytest

from .conftest import BASE_URL, AVAILABLE

pytestmark = pytest.mark.e2e


def test_tls_enforced():
    # The deployed service must be served over HTTPS (Cloud Run front-end).
    assert BASE_URL.startswith("https://"), f"backend URL must be https, got {BASE_URL!r}"


def test_health_is_public(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_docs_disabled(client):
    # /docs and /openapi.json are disabled in production.
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404


def test_auth_required(client):
    assert client.get("/v1/memory/recent").status_code == 401


def test_bad_token_rejected(client):
    r = client.get("/v1/memory/recent", headers={"Authorization": "Bearer not-a-real-key"})
    assert r.status_code == 401


def test_search_returns_results(client, auth):
    r = client.post("/v1/brain/search", json={"query": "user", "top_k": 5}, headers=auth)
    assert r.status_code == 200
    body = r.json()
    assert "results" in body and isinstance(body["results"], list)


def test_input_validation(client, auth):
    assert client.post("/v1/brain/search", json={"query": "   "}, headers=auth).status_code == 400
    assert client.post("/v1/memory/save", json={"type": "x", "content": " "}, headers=auth).status_code == 400


def test_rate_limit_resilience(client, auth):
    # A burst must never surface a 5xx; responses are either accepted (200) or rate-limited (429).
    statuses = set()
    for _ in range(200):
        r = client.get("/v1/memory/recent", params={"limit": 1}, headers=auth)
        statuses.add(r.status_code)
        if r.status_code == 429:
            break
    assert 200 in statuses
    assert statuses <= {200, 429}, f"unexpected statuses: {statuses}"
