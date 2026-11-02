"""NEXUS Neon backend — narrow HTTPS API between the Android app and Neon Postgres.

Purpose: remove direct JDBC from the device. The app calls this service; only this service
holds the Neon credentials. Reads use the `nexus_reader` URL; writes use the `nexus` URL. Every
statement is parameterized (no string interpolation of values).

Authentication: bearer key → named identity + scopes (auth.py). Endpoints enforce `read`/`write`
scopes; a per-identity rate limiter and a JSON-lines audit log (stdout) are always on.

Deploy anywhere (Cloud Run / VPS). Configure via environment:
  NEON_READ_URL           — read connection URL (Second Brain `chunks_v4` + `memory`)
  NEON_WRITE_URL          — write connection URL (`personal-ai-core.nexus_memory` / `nexus_conversations`)
  NEON_MEMORY_READ_URL    — independent SELECT-only connection to `personal-ai-core.nexus_memory`
  NEXUS_BACKEND_KEY       — legacy single bearer token (all scopes)
  NEXUS_KEYS              — JSON map {"<key>": {"name": ..., "scopes": ["read","write"]}}
  NEXUS_CONNECT_TIMEOUT   — DB connect timeout (default 10)
  NEXUS_RATE_LIMIT_PER_MIN — per-identity requests/min (default 120; 0 disables)
"""
import os

import psycopg
from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel
from fastapi.responses import JSONResponse

from auth import (
    READ_SCOPE,
    WRITE_SCOPE,
    Identity,
    RateLimiter,
    audit,
    authenticate,
    load_identities,
)
from sql_queries import (
    INSERT_CONVERSATION_SQL,
    INSERT_MEMORY_SQL,
    MAX_CONTENT_LEN,
    MAX_QUERY_LEN,
    MAX_ROLE_LEN,
    MAX_SESSION_ID_LEN,
    MAX_TAGS,
    MAX_TAG_LEN,
    MAX_TOP_K,
    MAX_TYPE_LEN,
    MEMORY_TYPES,
    MIN_TOP_K,
    RECALL_CAP,
    SEARCH_CHUNKS_SQL,
    SEARCH_MEMORY_SQL,
    SELECT_MEMORY_SQL,
    error_code,
    ilike_pattern,
    tags_literal,
)

app = FastAPI(
    title="NEXUS Neon backend",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)
_bearer = HTTPBearer(auto_error=False)

READ_URL = os.environ.get("NEON_READ_URL", "")
WRITE_URL = os.environ.get("NEON_WRITE_URL", "")
# Never fall back to the write credential for a read-only HTTP request.
MEMORY_READ_URL = os.environ.get("NEON_MEMORY_READ_URL", "")


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        return default


CONNECT_TIMEOUT = _int_env("NEON_CONNECT_TIMEOUT", 10)

IDENTITIES: dict[str, Identity] = load_identities()
RATE_LIMITER = RateLimiter(per_min=_int_env("NEXUS_RATE_LIMIT_PER_MIN", 120))


def _require_auth(request: Request, cred: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> Identity:
    ident = authenticate(IDENTITIES, cred.credentials if cred else None)
    if ident is None:
        raise HTTPException(status_code=401, detail="auth")
    request.state.identity = ident.name
    if not RATE_LIMITER.allow(ident.name):
        raise HTTPException(status_code=429, detail="rate")
    return ident


def require_scope(scope: str):
    def _dependency(request: Request, ident: Identity = Depends(_require_auth)) -> Identity:
        if scope not in ident.scopes:
            raise HTTPException(status_code=403, detail="forbidden")
        request.state.scope = scope
        return ident

    return _dependency


@app.middleware("http")
async def audit_middleware(request: Request, call_next):
    response = await call_next(request)
    if request.url.path != "/health":
        identity = getattr(request.state, "identity", None)
        scope = getattr(request.state, "scope", None)
        audit(identity, request.method, request.url.path, response.status_code, scope)
    return response


# Reject oversized request bodies before pydantic parses them (defense-in-depth against
# memory-exhaustion DoS). Content-Length covers the common client case; a chunked body still
# relies on the reverse proxy (Cloud Run) for a hard upper bound.
MAX_BODY_BYTES = 64 * 1024


@app.middleware("http")
async def limit_body_size(request: Request, call_next):
    content_length = request.headers.get("content-length")
    if content_length is not None and content_length.isdigit() and int(content_length) > MAX_BODY_BYTES:
        return JSONResponse(status_code=413, content={"detail": "body_too_large"})
    return await call_next(request)


def _connect(url: str) -> psycopg.Connection:
    return psycopg.connect(url, connect_timeout=CONNECT_TIMEOUT)


class SearchRequest(BaseModel):
    query: str
    top_k: int = 8


class MemorySaveRequest(BaseModel):
    type: str
    content: str
    tags: list[str] = []


class MirrorRequest(BaseModel):
    session_id: str
    role: str
    content: str


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/v1/brain/search", dependencies=[Depends(require_scope(READ_SCOPE))])
def brain_search(req: SearchRequest) -> dict:
    query = req.query.strip()
    if not query:
        raise HTTPException(status_code=400, detail="empty")
    if len(query) > MAX_QUERY_LEN:
        raise HTTPException(status_code=400, detail="query_too_long")
    top_k = max(MIN_TOP_K, min(req.top_k, MAX_TOP_K))
    pattern = ilike_pattern(query)
    try:
        with _connect(READ_URL) as conn:
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
        merged = sorted(chunks + memory, key=lambda x: x["score"], reverse=True)[:top_k]
        return {"results": merged}
    except psycopg.Error as e:
        raise HTTPException(status_code=502, detail=error_code(e))


@app.post("/v1/memory/save", dependencies=[Depends(require_scope(WRITE_SCOPE))])
def memory_save(req: MemorySaveRequest) -> dict:
    if not req.content.strip():
        raise HTTPException(status_code=400, detail="empty")
    if len(req.content) > MAX_CONTENT_LEN:
        raise HTTPException(status_code=400, detail="content_too_long")
    if len(req.type) > MAX_TYPE_LEN:
        raise HTTPException(status_code=400, detail="type_too_long")
    if req.type not in MEMORY_TYPES:
        raise HTTPException(status_code=400, detail="type_invalid")
    if len(req.tags) > MAX_TAGS or any(len(t) > MAX_TAG_LEN for t in req.tags):
        raise HTTPException(status_code=400, detail="tags_invalid")
    try:
        with _connect(WRITE_URL) as conn:
            with conn.cursor() as cur:
                cur.execute(INSERT_MEMORY_SQL, (req.type, req.content, tags_literal(req.tags)))
            conn.commit()
        return {"ok": True}
    except psycopg.Error as e:
        raise HTTPException(status_code=502, detail=error_code(e))


@app.get("/v1/memory/recent", dependencies=[Depends(require_scope(READ_SCOPE))])
def memory_recent(limit: int = Query(default=100, ge=1, le=RECALL_CAP)) -> dict:
    # A separately provisioned SELECT-only role is mandatory. Missing configuration
    # returns a stable unavailable status rather than downgrading to NEON_WRITE_URL.
    if not MEMORY_READ_URL:
        raise HTTPException(status_code=503, detail="memory_reader_unconfigured")
    try:
        with _connect(MEMORY_READ_URL) as conn:
            with conn.cursor() as cur:
                # Defense in depth: mark this transaction read-only even if a
                # credential is accidentally overprivileged.
                cur.execute("SET TRANSACTION READ ONLY")
                cur.execute(SELECT_MEMORY_SQL, (limit,))
                items = [{"type": r[0], "content": r[1]} for r in cur.fetchall()]
        return {"items": items}
    except psycopg.Error as e:
        raise HTTPException(status_code=502, detail=error_code(e))


@app.post("/v1/transcript/mirror", dependencies=[Depends(require_scope(WRITE_SCOPE))])
def transcript_mirror(req: MirrorRequest) -> dict:
    if not req.content.strip():
        raise HTTPException(status_code=400, detail="empty")
    if len(req.content) > MAX_CONTENT_LEN:
        raise HTTPException(status_code=400, detail="content_too_long")
    if len(req.role) > MAX_ROLE_LEN:
        raise HTTPException(status_code=400, detail="role_too_long")
    if len(req.session_id) > MAX_SESSION_ID_LEN:
        raise HTTPException(status_code=400, detail="session_id_too_long")
    try:
        with _connect(WRITE_URL) as conn:
            with conn.cursor() as cur:
                cur.execute(INSERT_CONVERSATION_SQL, (req.session_id, req.role, req.content))
            conn.commit()
        return {"ok": True}
    except psycopg.Error as e:
        raise HTTPException(status_code=502, detail=error_code(e))
