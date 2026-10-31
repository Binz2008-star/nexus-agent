# NEXUS Agent — Production hardening (proposals)

This document records the P1 security/reliability review and the proposed follow-ups. **Nothing
here modifies production Neon roles, schemas, secrets, branches, or backup settings** — those
require explicit owner approval.

## 1. Direct Android→PostgreSQL → authenticated backend API

**Current:** the app opens JDBC connections straight to Neon using user-supplied connection
strings (read role `nexus_reader` for Second Brain, write role `nexus` for memory/mirror).

**Risk:** any device with the (encrypted-at-rest but decryptable) URL can issue arbitrary SQL as
those roles; there is no per-request authorization, audit, or rate limiting at the data layer.

**Proposal (recommended):**

- Introduce a thin backend (e.g. Cloudflare Workers + PostgREST, or a small FastAPI/Supabase
  Edge Function) that owns the Neon credentials and exposes narrow, parameterized endpoints:
  `GET /search?q=&k=`, `POST /memories`, `GET /memories?q=&limit=`.
- The Android app authenticates to the backend with short-lived per-device tokens (not the Neon
  credentials). Neon is placed on a private network/VPC or its roles are scoped to the backend.
- Endpoints enforce allow-listed query shapes, bounds, and rate limits, and emit audit logs.

**Migration:** keep `NeonRepository`/`SecondBrainSql` as the domain seam; replace only the
`NeonJdbc` transport with an HTTP client implementation, so the repository, orchestration, and
tests are unchanged.

## 2. Writer role permission review

The `nexus` role currently has broad `UPDATE`/`DELETE` (and possibly `DROP`) on
`personal-ai-core`. The app only needs:

- `INSERT` + `SELECT` on `nexus_memory`
- `INSERT` on `nexus_conversations`

**Proposal:** restrict the `nexus` role to `SELECT, INSERT` on those two tables (no `UPDATE`,
`DELETE`, `DROP`, no schema DDL). Row ownership/`created_at` ordering already satisfies recall.
This is a **production change** — do not apply without owner approval and a backup.

## 3. Snapshot, recovery, and branch protection

**Backup/recovery:**

- Enable Neon point-in-time recovery (PITR) with a retention window appropriate to the data
  (≥ 7 days). Document the restore path and rehearse it in a non-production branch.
- Export `nexus_memory` and `nexus_conversations` periodically (e.g. nightly `pg_dump` to
  private object storage) as a second copy outside Neon.

**Branch protection (GitHub):**

- Protect `main`: require a pull request, at least one approving review, and the CI status checks
  (`build`, `instrumentation-tests`) before merge. Disable direct pushes.
- Consider a `CODEOWNERS` file for the security-sensitive paths (`data/repository`,
  `security/`, `.github/workflows`).

## 4. Credential import validation (implemented)

- `CredentialFileParser` now rejects oversized key tokens (`MAX_KEY_LENGTH = 256`) and any URL
  line that does not parse as a valid Neon connection (host + database present, secure
  `sslmode`), so malformed/insecure files can't pre-fill bad values.
- `ChatScreen` reads the import file through a bounded reader (`MAX_CREDENTIAL_FILE_BYTES = 64
  KiB`), returning null for oversized files instead of slurping unbounded content.

## 5. Credential confidentiality (verified)

- Neon/Google credentials are user-supplied and stored encrypted (Android Keystore AES-GCM); they
  are **not** in `BuildConfig`, `gradle.properties`, CI workflow, or the APK. `GOOGLE_OAUTH_CLIENT_ID`
  is a public identifier (not a secret) and is the only injected value.
- JDBC URLs are masked for display (`NeonConnectionParser.maskCredentials`). No code path logs
  decrypted connection strings or keys. See `app/build.gradle.kts` and
  `.github/workflows/build.yml`.

## Open items requiring owner decisions

- Approve and schedule the backend-API migration (§1).
- Approve the `nexus` role permission reduction (§2) — production change.
- Approve enabling PITR + the nightly export (§3).
- Enable GitHub branch protection on `main` (§3).
- Register a Google OAuth client id and perform the on-device redirect test.
