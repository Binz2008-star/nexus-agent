"""NEXUS Neon backend — narrow HTTPS API between the Android app and Neon Postgres.

Purpose: remove direct JDBC from the device. The app calls this service; only this
service holds the Neon credentials. Reads use the `nexus_reader` URL; writes use the
`nexus` URL. Every statement is parameterized (no string interpolation of values).

Deploy anywhere (Cloud Run / VPS). Configure via environment:
  NEON_READ_URL   — read connection URL (Second Brain `chunks_v4` + `memory`)
  NEON_WRITE_URL  — write connection URL (`personal-ai-core.nexus_memory` / `nexus_conversations`)
  NEXUS_BACKEND_KEY — bearer token the app must present

SQL mirrors `SecondBrainSql.kt` exactly, so staging parity tests can assert identical
rows to the existing JDBC path.
"""
import os

import psycopg
from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel

app = FastAPI(title="NEXUS Neon backend")
_bearer = HTTPBearer(auto_error=False)

READ_URL = os.environ.get("NEON_READ_URL", "")
WRITE_URL = os.environ.get("NEON_WRITE_URL", "")
API_KEY = os.environ.get("NEXUS_BACKEND_KEY", "")

# ---- SQL (mirrors SecondBrainSql.kt) ----
SEARCH_CHUNKS_SQL = (
    "SELECT project_id, file_path, chunk_type, content, similarity(content, %s) AS score "
    "FROM chunks_v4 WHERE content ILIKE %s ORDER BY score DESC LIMIT %s"
)
SEARCH_MEMORY_SQL = (
    "SELECT type, content, similarity(content, %s) AS score "
    "FROM memory WHERE content ILIKE %s ORDER BY score DESC LIMIT %s"
)
INSERT_CONVERSATION_SQL = (
    "INSERT INTO nexus_conversations (session_id, role, content) VALUES (%s, %s, %s)"
)
INSERT_MEMORY_SQL = (
    "INSERT INTO nexus_memory (type, content, tags) VALUES (%s, %s, %s::text[])"
)
SELECT_MEMORY_SQL = "SELECT type, content FROM nexus_memory ORDER BY created_at DESC LIMIT %s"

MIN_TOP_K, MAX_TOP_K = 1, 50
RECALL_CAP = 200


def _ilike_pattern(query: str) -> str:
    return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"


def _tags_literal(tags: list[str]) -> str:
    return "{" + ",".join('"' + t.replace('"', '""') + '"' for t in tags) + "}"


def _require_auth(cred: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> None:
    if not API_KEY or cred is None or cred.credentials != API_KEY:
        raise HTTPException(status_code=401, detail="unauthorized")


def _error_code(e: BaseException) -> str:
    msg = str(e)
    if "password authentication failed" in msg or "28P01" in msg:
        return "auth"
    if "does not exist" in msg:
        return "not_found"
    return "server"


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
    top_k = max(MIN_TOP_K, min(req.top_k, MAX_TOP_K))
    pattern = _ilike_pattern(query)
    try:
        with psycopg.connect(READ_URL) as conn:
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
        raise HTTPException(status_code=502, detail=_error_code(e))


@app.post("/v1/memory/save", dependencies=[Depends(_require_auth)])
def memory_save(req: MemorySaveRequest) -> dict:
    try:
        with psycopg.connect(WRITE_URL) as conn:
            with conn.cursor() as cur:
                cur.execute(INSERT_MEMORY_SQL, (req.type, req.content, _tags_literal(req.tags)))
            conn.commit()
        return {"ok": True}
    except psycopg.Error as e:
        raise HTTPException(status_code=502, detail=_error_code(e))


@app.get("/v1/memory/recent", dependencies=[Depends(_require_auth)])
def memory_recent(limit: int = Query(default=100, ge=1, le=RECALL_CAP)) -> dict:
    try:
        with psycopg.connect(WRITE_URL) as conn:
            with conn.cursor() as cur:
                cur.execute(SELECT_MEMORY_SQL, (limit,))
                items = [{"type": r[0], "content": r[1]} for r in cur.fetchall()]
        return {"items": items}
    except psycopg.Error as e:
        raise HTTPException(status_code=502, detail=_error_code(e))


@app.post("/v1/transcript/mirror", dependencies=[Depends(_require_auth)])
def transcript_mirror(req: MirrorRequest) -> dict:
    try:
        with psycopg.connect(WRITE_URL) as conn:
            with conn.cursor() as cur:
                cur.execute(INSERT_CONVERSATION_SQL, (req.session_id, req.role, req.content))
            conn.commit()
        return {"ok": True}
    except psycopg.Error as e:
        raise HTTPException(status_code=502, detail=_error_code(e))
