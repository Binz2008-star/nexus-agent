"""Unit tests for auth.py — identity loading, authentication, scopes, rate limiting, audit."""
import json

from auth import (
    ALL_SCOPES,
    READ_SCOPE,
    Identity,
    RateLimiter,
    audit,
    authenticate,
    load_identities,
)


def _env(monkeypatch, backend_key=None, keys=None):
    for name, value in (("NEXUS_BACKEND_KEY", backend_key), ("NEXUS_KEYS", keys)):
        if value is None:
            monkeypatch.delenv(name, raising=False)
        else:
            monkeypatch.setenv(name, value)


def test_load_identities_legacy_key(monkeypatch):
    _env(monkeypatch, backend_key="legacy-key")
    ids = load_identities()
    assert set(ids) == {"legacy-key"}
    assert ids["legacy-key"].name == "legacy"
    assert ids["legacy-key"].scopes == ALL_SCOPES


def test_load_identities_scoped_keys(monkeypatch):
    _env(monkeypatch, keys=json.dumps({
        "k1": {"name": "phone", "scopes": ["read", "write"]},
        "k2": {"name": "backup", "scopes": ["read"]},
    }))
    ids = load_identities()
    assert ids["k1"].name == "phone"
    assert ids["k1"].scopes == ALL_SCOPES
    assert ids["k2"].name == "backup"
    assert ids["k2"].scopes == frozenset({READ_SCOPE})


def test_load_identities_malformed_json_fails_closed(monkeypatch):
    _env(monkeypatch, backend_key="legacy", keys="{not valid json")
    ids = load_identities()
    assert set(ids) == {"legacy"}


def test_load_identities_empty(monkeypatch):
    _env(monkeypatch)
    assert load_identities() == {}


def test_load_identities_name_omitted_uses_hash_not_key(monkeypatch):
    _env(monkeypatch, keys=json.dumps({"supersecretkey": {"scopes": ["read"]}}))
    ids = load_identities()
    ident = ids["supersecretkey"]
    assert ident.name != "supersecretkey"
    assert ident.name.startswith("key-")
    assert "supersecretkey" not in ident.name


def test_load_identities_empty_scopes_fails_closed(monkeypatch):
    _env(monkeypatch, keys=json.dumps({"k1": {"name": "disabled", "scopes": []}}))
    ids = load_identities()
    assert ids["k1"].scopes == frozenset()


def test_load_identities_null_or_missing_scopes_are_denied(monkeypatch):
    _env(monkeypatch, keys=json.dumps({
        "k1": {"name": "phone", "scopes": None},
        "k2": {"name": "backup"},
        "k3": {"name": "disabled", "scopes": "invalid"},
    }))
    ids = load_identities()
    assert ids["k1"].scopes == frozenset()
    assert ids["k2"].scopes == frozenset()
    assert ids["k3"].scopes == frozenset()


def test_plain_string_legacy_key_is_unchanged(monkeypatch):
    _env(monkeypatch, keys=json.dumps({"legacy-config-key": "legacy"}))
    ids = load_identities()
    assert ids["legacy-config-key"].scopes == ALL_SCOPES


def test_authenticate_returns_identity_or_none():
    ids = {
        "k1": Identity(name="phone", scopes=ALL_SCOPES),
        "k2": Identity(name="backup", scopes=frozenset({READ_SCOPE})),
    }
    assert authenticate(ids, "k1").name == "phone"
    assert authenticate(ids, "k2").name == "backup"
    assert authenticate(ids, "nope") is None
    assert authenticate(ids, None) is None
    assert authenticate({}, "k1") is None


def test_rate_limiter_enforces_then_recovers():
    rl = RateLimiter(per_min=3)
    assert [rl.allow("a") for _ in range(3)] == [True, True, True]
    assert rl.allow("a") is False
    assert rl.allow("b") is True  # other identity unaffected


def test_rate_limiter_disabled_at_zero():
    rl = RateLimiter(per_min=0)
    assert all(rl.allow("a") for _ in range(100))


def test_rate_limiter_bounds_bucket_count():
    rl = RateLimiter(per_min=1, max_buckets=2)
    assert rl.allow("a") is True
    assert rl.allow("b") is True
    # Simulate "a" going idle: its single timestamp expired and was drained.
    rl._buckets["a"].clear()
    # A third identity trips the cap; the empty "a" bucket is evicted.
    assert rl.allow("c") is True
    assert "a" not in rl._buckets


def test_audit_emits_json_line_without_secrets(capsys):
    audit("phone", "POST", "/v1/memory/save", 200, "write")
    record = json.loads(capsys.readouterr().out.strip())
    assert record["identity"] == "phone"
    assert record["method"] == "POST"
    assert record["path"] == "/v1/memory/save"
    assert record["status"] == 200
    assert record["scope"] == "write"
    # The audit record must never carry a key or body.
    assert "key" not in record
    assert "content" not in record
