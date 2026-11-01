# NEXUS Neon backend

A narrow HTTPS API between the NEXUS Android app and Neon PostgreSQL. It removes direct
JDBC from the device: the app talks only to this service, and **only this service** holds the
Neon credentials. See `docs/NEON-BACKEND-MIGRATION.md` for the full plan.

## Endpoints (all bearer-authenticated except `/health`)

| Method | Path | Read/Write | Purpose |
|--------|------|-----------|---------|
| GET | `/health` | — | liveness |
| POST | `/v1/brain/search` | `NEON_READ_URL` | Second Brain `chunks_v4` + `memory` search |
| POST | `/v1/memory/save` | `NEON_WRITE_URL` | append a memory fact (`nexus_memory`) |
| GET | `/v1/memory/recent?limit=N` | `NEON_WRITE_URL` | recent memories (client ranks locally) |
| POST | `/v1/transcript/mirror` | `NEON_WRITE_URL` | best-effort conversation mirror |

## Run locally

```bash
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
export NEON_READ_URL='postgresql://nexus_reader:...@.../neondb?sslmode=require'
export NEON_WRITE_URL='postgresql://nexus:...@.../neondb?sslmode=require'
export NEXUS_BACKEND_KEY='<random>'
uvicorn main:app --host 0.0.0.0 --port 8080
```

## Least-privilege roles

- `NEON_READ_URL` should use the **`nexus_reader`** role (SELECT only).
- `NEON_WRITE_URL` should use a narrowly-scoped writer (INSERT on `nexus_memory` +
  `nexus_conversations` only — no `UPDATE`/`DELETE`/`DROP`).

## Staging parity test (before flipping the app)

1. Point the backend at the **same** Neon databases the app currently uses.
2. Run `POST /v1/brain/search` with a sample query and compare the returned rows (project/file/
   content/score) to the app's `NeonRepository.search` output for the same query — they must match
   exactly (both use `SecondBrainSql` / `similarity` + `ILIKE`).
3. `POST /v1/memory/save` + `GET /v1/memory/recent` must round-trip the same rows the app writes.
4. Confirm `transcript/mirror` inserts the same `nexus_conversations` rows.

## Auth

`NEXUS_BACKEND_KEY` is the bearer token the app presents. Generate a long random value and keep it
out of the repo. The app will send it in `Authorization: Bearer …` and it is never logged.
