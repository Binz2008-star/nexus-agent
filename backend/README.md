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

## Connection-string formats (important)

The backend (psycopg) and the app (pgjdbc) use **different** URL formats for the same Neon instance:

- **Backend** (`NEON_READ_URL`/`NEON_WRITE_URL`): psycopg/libpq URI —
  `postgresql://nexus_reader:password@host/db?sslmode=require`.
- **App** (direct JDBC, being replaced): pgjdbc — `jdbc:postgresql://host/db?sslmode=require&user=nexus_reader&password=…`.

pgjdbc does **not** accept the `user:password@host` form (it treats the whole thing as the
hostname). When wiring the backend, drop the `jdbc:` prefix and pass the Neon connection string as
the psycopg/libpq URI (userinfo form is fine for psycopg).

## Testing

- Unit + endpoint tests (no DB): `pytest -m "not integration"`.
- Integration/parity tests (disposable Postgres): `docker compose -f docker-compose.test.yml up -d`
  then `pytest -m integration` (see `tests/integration/`). The `test_sql_parity.py` reads
  `SecondBrainSql.kt` directly to keep the backend SQL in lockstep with the Android JDBC path.
- The Android side has a matching `NeonJdbcIntegrationTest` (real pgjdbc against the same
  disposable Postgres), run by the `jdbc-integration` CI job — together they prove JDBC == HTTP.

## Staging parity test (before flipping the app)

1. Point the backend at the **same** Neon databases the app currently uses.
2. Run `POST /v1/brain/search` with a sample query and compare the returned rows (project/file/
   content/score) to the app's `NeonRepository.search` output for the same query — they must match
   exactly (both use `SecondBrainSql` / `similarity` + `ILIKE`).
3. `POST /v1/memory/save` + `GET /v1/memory/recent` must round-trip the same rows the app writes.
4. Confirm `transcript/mirror` inserts the same `nexus_conversations` rows.

## Auth

The app presents a bearer token in `Authorization: Bearer …`. Each token maps to a named identity
with scopes (`read` = search/recent, `write` = save/mirror).

- `NEXUS_BACKEND_KEY` — legacy single token (all scopes).
- `NEXUS_KEYS` — JSON `{"<token>": {"name": "<device>", "scopes": ["read","write"]}}` for
  device-specific identity and scoped authorization (a backup device can be read-only).
- Rate limiting: `NEXUS_RATE_LIMIT_PER_MIN` (default 120) per identity; `0` disables.
- Audit: every authenticated request writes a JSON line to stdout (identity/method/path/status/
  scope only — never bodies or keys). Cloud Run ships stdout to Cloud Logging.

See `auth.py` and `cloudrun/README.md` for deploy/secret wiring. Never commit a token.
