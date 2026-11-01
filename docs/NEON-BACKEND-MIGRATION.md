# Neon Backend Migration Plan (secure architecture)

**Status:** Phase A implemented (backend + Android HTTP client behind a flag, off by default).
Phases B–E pending. No production-credential change, no user-data migration yet.
**Goal:** remove direct Android→Neon PostgreSQL access so database credentials and privileged
SQL never live on the device.

## Implementation status (2026-11-01)

- ✅ **Backend** (`backend/main.py` + `backend/sql_queries.py`): the four endpoints below, hardened
  (constant-time bearer, connect timeout, input caps), 21 pytest tests, `backend-tests` CI job.
  Note: endpoint is `/v1/memory/recent` (not `/v1/memory/recall`); `recallMemory` ranking stays
  on-device (`MemoryRecall`), so the backend only needs the bounded recent-fetch.
- ✅ **Android** `NeonHttpClient` (+ `NeonBackendClient` interface) returns the same domain types
  as the JDBC path. `NeonRepository` routes to HTTP when `use_neon_backend` is on and a client is
  configured — JDBC stays the default. Settings UI (URL + key + toggle). No Neon credential or
  backend token is compiled into the APK.
- ⏳ **Parity vs staging** and **Phase B/C/D/E** require Neon access + a deployed backend (owner).


## 1. Current state (risk)

`NeonRepository` (`data/repository/NeonRepository.kt`) opens JDBC connections to Neon using a
connection URL that is AES-GCM-encrypted at rest but **decryptable on-device** (Android Keystore
key is non-exportable, but the app can decrypt it). The `nexus` role is documented as having broad
`UPDATE`/`DELETE` (possibly `DROP`). A rooted/compromised device — or the app itself if
prompt-injected — can run arbitrary SQL as `nexus_reader`/`nexus`.

Uses:
- `NeonRepository.search` (Second Brain `chunks_v4` + `memory` read)
- `NeonRepository.saveMemory` / `recallMemory` (`personal-ai-core.nexus_memory`)
- `ConversationMirror` (opt-in transcript mirror)

## 2. Target architecture

Introduce a thin **backend API** between the app and Neon:

```
Android app ──HTTPS──▶ NEXUS backend (VPS/Cloud Run) ──secrets──▶ Neon Postgres
```

- Backend holds Neon credentials (secret-manager / env), never the device.
- Backend exposes narrow, authenticated endpoints mirroring current queries:
  - `POST /v1/brain/search`  (read-only `chunks_v4` + `memory` search)
  - `POST /v1/memory/save`   (append a memory fact — scoped, parameterized)
  - `POST /v1/memory/recall` (bounded personal-memory recall)
  - `POST /v1/transcript/mirror` (opt-in mirror; least-privilege write)
- Backend uses **parameterized SQL** with a `nexus_reader`-only role for reads and a separate
  narrowly-scoped writer (no `DROP`, no arbitrary `UPDATE`/`DELETE`).
- Android auth to the backend: per-device key or OAuth token (reuse the existing OAuth path), not
  a shared DB password.

## 3. Migration plan (phased, non-disruptive)

1. **Phase A — shadow the backend.** Deploy the backend alongside the existing JDBC path; app keeps
   JDBC as the default, backend is exercised off-by-default behind a flag. No data change.
2. **Phase B — flip reads.** `search` + `recallMemory` go through the backend; JDBC kept as fallback
   for one release. Verify parity (same results) on a staging Neon branch.
3. **Phase C — flip writes.** `saveMemory` + transcript mirror go through the backend. Rotate the
   on-device Neon write credential; keep read cred as a short-lived fallback.
4. **Phase D — remove JDBC.** Drop `NeonJdbcClient`, `postgresql` dependency, and the Neon URL
   fields from Settings (replace with a single backend URL). Remove on-device Neon secrets.
5. **Phase E — least-privilege roles.** Re-scope `nexus` to read-only; introduce a `nexus_writer`
   role limited to the exact append statements.

Each phase is independently shippable and reversible; no existing user data is moved or rewritten.

## 4. What must NOT change

- Production Neon credentials and the existing `nexus`/`nexus_reader` roles stay as-is until the
  backend is live and parity is proven.
- No SQL schema change in `chunks_v4` / `memory` / `nexus_memory` tables.

## 5. Owner decisions required

1. Where the backend runs (Cloud Run / VPS / the Hermes host) and its auth model (device key vs OAuth).
2. Confirm the exact `nexus` role privileges today (the hardening doc is ambiguous on `DROP`).
3. Go-ahead to create the `nexus_writer` role + a staging Neon branch for parity testing.

## 6. Acceptance criteria

- Device contains **no** Neon connection string or PostgreSQL credential (post-migration).
- All four endpoints parameterize SQL; no raw string interpolation.
- Parity test: backend `search` returns identical rows to the current JDBC path on a sample corpus.
- `memory_save` and transcript mirror still function with the `nexus_writer` role.
