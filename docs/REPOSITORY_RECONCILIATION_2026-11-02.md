# NEXUS X — Repository reconciliation and release gates

Audit date: 2026-11-02 (UTC+04). Repository: `Binz2008-star/nexus-agent`.
This document records **source/CI evidence**, not a claim of deployed cloud services
or physical Android acceptance. Preserve history and avoid branch deletion until
owner-approved release tagging and recovery instructions exist.

## Inventory and integration decisions

At start of audit: **12 open issues** (excluding PRs), **42 branches** including
`main`, and **13 open draft PRs**. At completion of the five approved merges,
`main` = `c431d9ed2d049da02041c25cd110f45a793781b2`.
GitHub Actions run [41380603886](https://github.com/Binz2008-star/nexus-agent/actions/runs/41380603886)
passed **5/5**: Android build, emulator instrumentation, backend unit,
backend integration and JDBC integration.

| Canonical merged PR | Merge SHA | Contents |
|---|---|---|
| [#47](https://github.com/Binz2008-star/nexus-agent/pull/47) | `d31b42c` | UI polish, Drive identity search, Gmail action evidence and encrypted crash-safe drafts |
| [#43](https://github.com/Binz2008-star/nexus-agent/pull/43) | `e0aad78` | Staging manifest, configuration validation and environment isolation |
| [#44](https://github.com/Binz2008-star/nexus-agent/pull/44) | `ab75ef1` | Separate read-only personal-memory HTTP endpoint credential, disposable DB privilege tests |
| [#35](https://github.com/Binz2008-star/nexus-agent/pull/35) | `c530c5a` | OAuth session revalidation, bounded Hermes SSE/REST, fail-closed HTTPS routing, backend default-deny structured scopes |
| [#37](https://github.com/Binz2008-star/nexus-agent/pull/37) | `c431d9e` | Local Android TTS voice picker, persistent voice settings, lifecycle safeguards |

Closed/superseded draft PRs **#18, #27, #36, #38, #42, #45, #46** after review.
Their branches remain intentionally undeleted for provenance. `PR #21` stays
open as the **staging HTTPS parity test** workstream. `PR #50` hardens the
Android *release* transport by disabling direct JDBC when `BuildConfig.DEBUG=false`;
it remains subject to its own CI result and merge review.

### Branch handling

The initial 42-branch inventory includes the five canonical feature/integration
branches above; implementation branches for earlier UI, auth, persona, memory,
security and backend revisions; and `main`. Treat every non-main branch as
**preserved**, not approved for deletion. Closing a superseded PR is not
equivalent to deleting its branch. Review ancestry, unique commits,
reproducible CI and artifact provenance before any retention/cleanup decision.

## Every still-open issue: evidence and remaining acceptance

| Issue | Classification | Current evidence | Remaining release gate |
|---|---|---|---|
| [#6](https://github.com/Binz2008-star/nexus-agent/issues/6) | P0, partial | HTTPS API exists, fail-closed routing merged; PR #50 for release-only transport | Authenticated *deployed* HTTPS E2E; ensure no client privileged URL and scoped DB role |
| [#7](https://github.com/Binz2008-star/nexus-agent/issues/7) | P0, partial | Room audit WorkManager read-only checkpoints merged; Gmail one-shot local approval | General COMMIT durable resume, provider-side idempotency, cancellation/crash tests |
| [#8](https://github.com/Binz2008-star/nexus-agent/issues/8) | P0, blocked | Emulator CI green; APK artifact built | Physical OnePlus 12 upgrade-over-install, OAuth, history, secrets, voice, Neon and Gmail evidence |
| [#9](https://github.com/Binz2008-star/nexus-agent/issues/9) | P1, not tested | CI fork-signing fallback code present | Real isolated external-fork security test without production signing secrets |
| [#10](https://github.com/Binz2008-star/nexus-agent/issues/10) | P0, blocked | Test APK signing pipeline exists | Audited historical key exposure/rotation; separate protected production release key |
| [#22](https://github.com/Binz2008-star/nexus-agent/issues/22) | P0, partial | Room conversation restore/settings and Google silent revalidation merged | Force-stop/restart/upgrade real-device test; no data wipe |
| [#23](https://github.com/Binz2008-star/nexus-agent/issues/23) | P1, partial | Persisted Mashaaer Soft Mode and command tests | Real-device confirmation of style, data continuity and safety invariance |
| [#24](https://github.com/Binz2008-star/nexus-agent/issues/24) | P1, partial | System TextToSpeech voice picker merged | Actual installed Arabic voice audition, stop/restart and offline quality; ONNX unvalidated |
| [#26](https://github.com/Binz2008-star/nexus-agent/issues/26) | research backlog | Source-reviewed OSS patterns and staged roadmap | Explicit selection/benchmark per feature; no blanket SDK/dependency install |
| [#39](https://github.com/Binz2008-star/nexus-agent/issues/39) | P0, partial | Deterministic Gmail result and encrypted draft state merged | Exact provider message-ID receipt and physical crash/approval/timeout tests |
| [#40](https://github.com/Binz2008-star/nexus-agent/issues/40) | P1, partial | Filtered bilingual identity search merged | Real Drive owner disambiguation on device; avoid exposing third-party ID |
| [#41](https://github.com/Binz2008-star/nexus-agent/issues/41) | P0, blocked | Staging manifest and separate SELECT-only memory transport merged | Neon staging compute/role, Secret Manager IAM and Cloud Run HTTPS tests; no billable deploy without separate authorization |

## Operational rules

1. **Code merged into main != production release.** No real-user device, Google
   OAuth, Cloud Run or Neon staging compute acceptance is inferred from CI.
2. **No silent downgrade** from selected HTTPS to privileged JDBC. Release builds
   must block direct PostgreSQL connections; debug-only diagnostics are not a
   substitute for authenticated backend E2E.
3. Approval-gated Gmail sends: one authorized POST, model output never proves
   acceptance, UNKNOWN network outcomes never trigger automatic resend.
   `SENT` is not proof of recipient delivery.
4. Do not delete Room/DataStore/Keystore data, sign a production release using
   a test key, enable unreviewed microphone permissions, or distribute an
   unvalidated external voice model.
5. No live Cloud Run/Neon role/Secret Manager writes or costs without a
   separate deployment plan, budget and owner review.

## Next highest-priority execution

- Pass PR #50's exact-head CI and merge if green.
- Close #6's backend E2E using **isolated staging** and true least-privilege roles
  after provisioning/billing approval.
- Resolve #7/#39's durable idempotency and Gmail provider message-ID receipts.
- Obtain physical OnePlus 12 installation, settings/chat persistence and voice
  acceptance before distributing anything as production.
