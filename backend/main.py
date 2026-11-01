"""NEXUS Neon backend — narrow HTTPS API between the Android app and Neon Postgres.

Purpose: remove direct JDBC from the device. The app calls this service; only this
service holds the Neon credentials. Reads use the `nexus_reader` URL; writes use the
`nexus` URL. Every statement is parameterized (no string interpolation of values).

Deploy anywhere (Cloud Run / VPS). Configure via environment:
  NEON_READ_URL     — read connection URL (Second Brain `chunks_v4` + `memory`)
  NEON_WRITE_URL    — write connection URL (`personal-ai-core.nexus_memory` / `nexus_conversations`)
  NEXUS_BACKEND_KEY — bearer token the app must present
"""
import os
import secrets

import psycopg
from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel

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
    MIN_TOP_K,
    RECALL_CAP,
    SEARCH_CHUNKS_SQL,
    SEARCH_MEMORY_SQL,
    SELECT_MEMORY_SQL,
    error_code,
    ilike_pattern,
    tags_literal,
)

app = FastAPI(title="NEXUS Neon backend")
_bearer = HTTPBearer(auto_error=False)

READ_URL = os.environ.get("NEON_READ_URL", "")
WRITE_URL = os.environ.get("NEON_WRITE_URL", "")
API_KEY = os.environ.get("NEXUS_BACKEND_KEY", "")
CONNECT_TIMEOUT = int(os.environ.get("NEON_CONNECT_TIMEOUT", "10"))


def _require_auth(cred: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> None:
    # Constant-time compare; also fail closed when no key is configured.
    if not API_KEY or cred is None or not secrets.compare_digest(cred.credentials, API_KEY):
        raise HTTPException(status_code=401, detail="unauthorized")


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


@app.post("/v1/brain/search", dependencies=[Depends(_require_auth)])
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


@app.post("/v1/memory/save", dependencies=[Depends(_require_auth)])
def memory_save(req: MemorySaveRequest) -> dict:
    if not req.content.strip():
        raise HTTPException(status_code=400, detail="empty")
    if len(req.content) > MAX_CONTENT_LEN:
        raise HTTPException(status_code=400, detail="content_too_long")
    if len(req.type) > MAX_TYPE_LEN:
        raise HTTPException(status_code=400, detail="type_too_long")
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


@app.get("/v1/memory/recent", dependencies=[Depends(_require_auth)])
def memory_recent(limit: int = Query(default=100, ge=1, le=RECALL_CAP)) -> dict:
    try:
        with _connect(WRITE_URL) as conn:
            with conn.cursor() as cur:
                cur.execute(SELECT_MEMORY_SQL, (limit,))
                items = [{"type": r[0], "content": r[1]} for r in cur.fetchall()]
        return {"items": items}
    except psycopg.Error as e:
        raise HTTPException(status_code=502, detail=error_code(e))


@app.post("/v1/transcript/mirror", dependencies=[Depends(_require_auth)])
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
