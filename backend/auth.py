"""Authentication, scoped authorization, rate limiting, and audit for the NEXUS backend.

Identity model: each API key maps to a named identity with a set of scopes. Keys are supplied via
environment (injected from Secret Manager on Cloud Run), never committed:

  NEXUS_BACKEND_KEY   legacy single key (all scopes: read + write)
  NEXUS_KEYS          JSON: {"<api-key>": {"name": "<device>", "scopes": ["read", "write"]}}

The audit log is JSON Lines on stdout (Cloud Run captures stdout → Cloud Logging). It records
identity/method/path/status/scope only — never request bodies, queries, or keys.
"""
import hashlib
import json
import os
import secrets
import sys
import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass

READ_SCOPE = "read"
WRITE_SCOPE = "write"
ALL_SCOPES = frozenset({READ_SCOPE, WRITE_SCOPE})


@dataclass(frozen=True)
class Identity:
    name: str
    scopes: frozenset


def _safe_name(key: str, meta) -> str:
    """Never expose the raw key as an identity name (it is written to the audit log)."""
    if isinstance(meta, dict):
        name = meta.get("name")
        if isinstance(name, str) and name.strip():
            return name.strip()
    return "key-" + hashlib.sha256(key.encode()).hexdigest()[:12]


def _scopes(meta) -> frozenset:
    """Resolve scopes. Fails closed: an explicit empty scope list grants nothing."""
    if not isinstance(meta, dict):
        return ALL_SCOPES  # explicitly documented legacy string form only
    raw = meta.get("scopes")
    if raw is None:
        # Fail closed for scoped JSON identities: missing/null grants no permissions.
        return frozenset()
    if isinstance(raw, str):
        return frozenset({raw}) if raw in (READ_SCOPE, WRITE_SCOPE) else frozenset()
    try:
        return frozenset(s for s in raw if s in (READ_SCOPE, WRITE_SCOPE))
    except TypeError:
        return frozenset()


def load_identities() -> dict[str, Identity]:
    """Returns {api_key: Identity}. Fails closed: malformed NEXUS_KEYS leaves only the legacy key."""
    ids: dict[str, Identity] = {}
    legacy = (os.environ.get("NEXUS_BACKEND_KEY") or "").strip()
    if legacy:
        ids[legacy] = Identity(name="legacy", scopes=ALL_SCOPES)

    raw = (os.environ.get("NEXUS_KEYS") or "").strip()
    if raw:
        try:
            data = json.loads(raw)
            for key, meta in data.items():
                if not isinstance(key, str) or not key:
                    continue
                ids[key] = Identity(name=_safe_name(key, meta), scopes=_scopes(meta))
        except (json.JSONDecodeError, TypeError, AttributeError):
            pass  # fail closed
    return ids


def authenticate(identities: dict[str, Identity], token: str | None) -> Identity | None:
    """Constant-time compare against every key (no early length leak, no per-key short-circuit)."""
    if not token:
        return None
    match: Identity | None = None
    for key, ident in identities.items():
        if secrets.compare_digest(key, token):
            match = ident
    return match


class RateLimiter:
    """Sliding-window request limiter, keyed by identity name. Thread-safe."""

    def __init__(self, per_min: int, max_buckets: int = 1024):
        self.per_min = per_min
        self.max_buckets = max_buckets
        self._buckets: dict[str, deque] = defaultdict(deque)
        self._lock = threading.Lock()

    def allow(self, name: str) -> bool:
        if self.per_min <= 0:
            return True
        now = time.monotonic()
        with self._lock:
            q = self._buckets[name]
            while q and now - q[0] > 60.0:
                q.popleft()
            if len(q) >= self.per_min:
                return False
            q.append(now)
            if len(self._buckets) > self.max_buckets:
                self._evict_idle()
            return True

    def _evict_idle(self) -> None:
        """Drop empty (idle) buckets so memory stays bounded under identity churn."""
        for key in [k for k, q in self._buckets.items() if not q]:
            del self._buckets[key]


def audit(name: str | None, method: str, path: str, status: int, scope: str | None) -> None:
    """Emit one JSON audit record to stdout. No bodies, queries, or keys are ever included."""
    record = {
        "ts": round(time.time(), 3),
        "identity": name,
        "method": method,
        "path": path,
        "status": status,
        "scope": scope,
    }
    print(json.dumps(record, separators=(",", ":")), file=sys.stdout, flush=True)
