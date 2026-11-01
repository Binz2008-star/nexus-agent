# Hermes Agent integration — Production-readiness assessment

**Honest status: NOT production-ready.** The code is implemented and unit-tested, but it has not
been verified end-to-end against a real Hermes server or on a physical Android device, and there
are deliberate, documented safety/operational gaps that require the owner.

## What is implemented (this diff)

- `HermesRemoteClient` (OkHttp) + DTOs + SSE parser + event mapper: capabilities, health,
  chat/completions, runs create/status/events/approval/stop, with timeouts, cancellation, and
  bounded reconnect + status reconciliation.
- Encrypted bearer key (`SecretStorageRepository`) + non-secret endpoint (`AppPreferencesRepository`);
  no privileged key in the APK; HTTPS enforced for non-loopback endpoints (`HermesSafety`).
- Engine selector (`DeepSeek` / `Hermes (Beta)`) gated on a verified connection; live task stage,
  tool progress, cancellation, and approval cards bridged from Hermes approval events.
- Safety: whitelist-only audit log (no secrets), read-only-first posture, no silent transfer of
  Gmail/memory/Neon data, approval fails closed.
- Unit tests: SSE parser, event mapper, endpoint safety, audit log, models, client (MockWebServer),
  repository state.

## What is NOT verified

- **No real Hermes server test.** The client is tested against MockWebServer only; the exact
  `approval.request` / `pending_controls` field spellings and terminal-event payloads are modeled
  from the docs and must be confirmed against a live server.
- **No on-device E2E.** No Android build or instrumented run of the Hermes path (CI must run first;
  a device with a reachable Hermes host is required for a true end-to-end pass).
- **SSE reconnect edge cases** (server restart mid-run, `unknown` run status, `resolve-unknown`)
  are implemented defensively but untested.

## Known gaps / owner action required

1. **Server-side read-only profile.** The client cannot disable host tools; a restricted Hermes
   profile must be configured on the host (see `docs/hermes-agent-deployment.md`). Without this,
   a remote Hermes server has full tool access including the terminal.
2. **No push / no CI run yet.** The branch is local; CI (`./gradlew test`, `assembleDebug`,
   `connectedDebugAndroidTest`) has not run. A push (explicitly approved) is required to produce
   CI + build evidence.
3. **Approval semantics.** The client sends `choice: once|deny`. The server's exact accepted
   `choice` values and the `request_id` vs `prompt_id` identity must be confirmed against the live
   server; a `choice` mismatch would fail closed (good) but block legitimate approvals.
4. **Error-string mapping** for Hermes failure modes is best-effort; a live pass should tune
   `HermesError.codeOf` and the localized strings.
5. **Rate/concurrency.** The server caps concurrent runs (429); the client does not queue/back off
   yet beyond the reconnect backoff.

## Recommendation

Merge only after: (a) a read-only Hermes profile is configured on a host, (b) CI is green on the
branch, and (c) a manual end-to-end pass on a device (send → tool progress → approval → cancel →
error/reconnect) is recorded. Treat "Hermes (Beta)" as a beta feature until then.
