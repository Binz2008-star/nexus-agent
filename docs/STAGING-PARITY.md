# Neon Staging Parity — branch IDs + run procedure

**Status: READY — staging branches are reachable and data-verified (2026-11-01).** Read access and
data parity with production were confirmed via `psql` (`chunks_v4`=9577, `memory`=31, `nexus_memory`=1,
`nexus_conversations`=1) and the search SQL returns correct ranked rows. Live parity + memory-persistence
tests remain the next step (procedure below). This doc records the exact target branch IDs and procedure.

## Target staging branches (CTO-verified)

| Role | Neon project ID | Branch ID |
|------|-----------------|-----------|
| **Second Brain** (read: `chunks_v4` + `memory`) | `misty-sun-80388989` | `br-snowy-butterfly-av3rwiuh` |
| **Personal AI Core** (write: `nexus_memory` / `nexus_conversations`) | `jolly-math-11598326` | `br-autumn-haze-b5ecvgmj` |

Mapping to the backend's env vars once compute endpoints exist:

- `NEON_READ_URL`  → Second Brain branch (`misty-sun-80388989` / `br-snowy-butterfly-av3rwiuh`)
- `NEON_WRITE_URL` → Personal AI Core branch (`jolly-math-11598326` / `br-autumn-haze-b5ecvgmj`)

## What is already proven (offline)

- **SQL byte-parity**: `backend/tests/test_sql_parity.py` reads `SecondBrainSql.kt` directly and
  asserts the backend SQL matches the Android JDBC path (modulo `?`→`%s`). ✅ passes on Python 3.14.
- **HTTP parity + permissions + error recovery**: `backend/tests/integration/` (9 tests) against a
  disposable PostgreSQL (`docker-compose.test.yml`). ✅ green in CI.
- **JDBC parity**: Android `NeonJdbcIntegrationTest` runs the real pgjdbc path against the same
  disposable PostgreSQL. ✅ green in CI.

## Run procedure (when staging connections are available)

```bash
cd backend
export NEON_READ_URL='<staging Second Brain read connection>'
export NEON_WRITE_URL='<staging Personal AI Core write connection>'
export NEXUS_BACKEND_KEY='<staging backend token>'

# Roles/db names differ on Neon and it exposes no superuser — override the defaults:
export NEXUS_TEST_READ_ROLE='<staging read role>'
export NEXUS_TEST_READ_PASSWORD='<staging read password>'
export NEXUS_TEST_WRITE_ROLE='<staging write role>'
export NEXUS_TEST_WRITE_PASSWORD='<staging write password>'
export NEXUS_TEST_READ_DB='<staging Second Brain db>'
export NEXUS_TEST_WRITE_DB='<staging Personal AI Core db>'
# A restricted verifier role that can SELECT nexus_conversations (Neon has no superuser):
export NEXUS_TEST_ADMIN_ROLE='<staging verifier role>'
export NEXUS_TEST_ADMIN_PASSWORD='<staging verifier password>'
export NEXUS_TEST_ADMIN_DB='<staging Personal AI Core db>'

pytest -m integration -q
```

These role/db parameters were added in `tests/integration/conftest.py` (defaults unchanged, so the
existing disposable-env CI path is unaffected). Passwords in the swapped URLs are percent-encoded,
so Neon-generated passwords with special characters are handled.

> **Credential hygiene:** `export` writes plaintext passwords into shell history and
> `/proc/<pid>/environ`. Prefer `read -rs NEXUS_TEST_WRITE_PASSWORD` (or `HISTCONTROL=ignorespace`),
> or load values from a 0600 file outside the repo tree.

> **Cross-branch roles:** the permission tests deliberately point the *read* role at the write DB and
> the *write* role at the read DB. On Neon these are **separate projects**, so the staging read role
> must also be provisioned on the Personal AI Core branch and the staging write role on the Second
> Brain branch (with grants that reproduce the expected `permission denied`).

## Hard guard — never write to production

- These integration tests perform **writes** (`memory_save`, `transcript_mirror`). Point them only
  at the staging branch connection strings above.
- **Never** set `NEON_WRITE_URL`/`NEXUS_TEST_*` to a production connection string.
- Production credentials and the existing `nexus`/`nexus_reader` roles remain untouched (see
  `NEON-BACKEND-MIGRATION.md` §4).

## Known caveat

`test_transcript_mirror_roundtrip` verifies the mirror write via a privileged role because
`nexus_writer` cannot SELECT `nexus_conversations` by design. On Neon (no `postgres` superuser), a
restricted **verifier role** with SELECT on `nexus_conversations` must be provisioned on the staging
Personal AI Core branch, and supplied via `NEXUS_TEST_ADMIN_ROLE`/`NEXUS_TEST_ADMIN_PASSWORD`.
