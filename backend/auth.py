"""Authentication, scoped authorization, rate limiting, and audit for the NEXUS backend.

Identity model: each API key maps to a named identity with a set of scopes. Keys are supplied via
environment (injected from Secret Manager on Cloud Run), never committed:

  NEXUS_BACKEND_KEY   legacy single key (all scopes: read + write)
  NEXUS_KEYS          JSON: {"<api-key>": {"name": "<device>", "scopes": ["read", "write"]}}

The audit log is JSON Lines on stdout (Cloud Run captures stdout → Cloud Logging). It records
identity/method/path/status/scope only — never request bodies, queries, or keys.
"""
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
                if isinstance(meta, dict):
                    name = str(meta.get("name") or key)
                    scopes = set(meta.get("scopes") or [READ_SCOPE, WRITE_SCOPE])
                else:
                    name = key
                    scopes = set(ALL_SCOPES)
                ids[key] = Identity(name=name, scopes=frozenset(scopes))
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

    def __init__(self, per_min: int):
        self.per_min = per_min
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
            return True


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
