# NEXUS Agent — Integration & Release Readiness Report

**Date:** 2026-11-01
**Branch under test:** `integration/audit` (merge of #12 + #13 + #14 + #15 onto `main` @ `c21bd78`)
**CI:** [run 41294495877](https://github.com/Binz2008-star/nexus-agent/actions/runs/41294495877) — `build` ✅ + `instrumentation-tests` ✅

---

## 1. Executive summary

All four draft PRs were merged into an isolated integration branch. The **combined build compiles, all unit
tests pass, and the emulator instrumentation tests pass.** Individual CI green is therefore confirmed at the
combined level. Release readiness is a separate question: two pre-existing security items and a block of
dead code remain, and no real-device / live-server validation has been possible from here.

**Overall verdict: NO-GO for production release** (GO for continued development / preview builds after the
below security items are resolved).

---

## 2. Compatibility assessment (PR overlaps + conflicts)

Shared files across PRs (conflict surface):

| File | #12 Hermes | #13 GWS | #14 web_fetch | #15 PostHog | Resolution |
|------|:---:|:---:|:---:|:---:|---|
| `app/build.gradle.kts` | ✅ | ✅ | ✅ | ✅ | merged (union of deps; dropped stale `appAuthRedirectScheme` placeholder after GWS removed AppAuth) |
| `ui/ChatScreen.kt` | ✅ | ✅ | ✅ | ✅ | merged (union of tool registrations + Hermes/PostHog wiring) |
| `data/repository/AppPreferencesRepository.kt` | ✅ | | | ✅ | auto-merged (Hermes endpoint/engine + PostHog consent coexist) |
| `data/connectors/GoogleAuthManager.kt` | ✅(import) | ✅(rewrite) | | | took GWS rewrite (Google Identity Services; no kotlinx.serialization) |
| `AndroidManifest.xml` | | ✅ | | ✅ | auto-merged (`.NexusApplication` + GWS manifest entries) |
| `MainActivity.kt` | | ✅ | | | GWS change (Google Identity Services) |

Build-dependency compatibility: PostHog `3.+` pulled Kotlin-2.1 metadata → **fixed** by pinning
`com.posthog:posthog-android:3.38.0` (last release on kotlin-stdlib 1.8.x). No other dependency conflicts.

---

## 3. Per-PR status

| PR | Branch | Individual CI | Integration CI | Notes |
|----|--------|:---:|:---:|---|
| #12 Hermes | `feature/hermes-agent-backend` | ✅ | ✅ | BLOCKER fixed (failed runs no longer shown as success); approvals hardened |
| #13 Google Workspace | `feat/gmail-google-identity-authorization` | ✅ | ✅ | other agent; rewrote GoogleAuthManager to Google Identity Services |
| #14 web_fetch | `feature/web-fetch` | ✅ | ✅ | SSRF DNS-pinning + regression tests added |
| #15 PostHog | `feat/posthog-opt-in-analytics` | ✅ | ✅ | build failure fixed (pinned 3.38.0) |

---

## 4. Requirement status (PASS / FAIL / BLOCKED / NOT VERIFIED)

| # | Requirement | Status | Evidence |
|---|-------------|--------|----------|
| 1 | Audit main | ✅ PASS | `explore` audit: chat/stream/web-search/Neon/Gmail/memory/tool-loop/approval all implemented; dead code + 2 security gaps identified (§6) |
| 2 | Review 4 PRs for overlaps | ✅ PASS | §2 — 3 shared files resolved in `integration/audit` |
| 3 | Diagnose PR #15 build | ✅ PASS | Kotlin-2.1 metadata from `posthog-android:3.+`; pinned 3.38.0; CI green |
| 4 | Validate Hermes vs real server | ⛔ BLOCKED | Termux Hermes API server cannot start: `aiohttp not installed` (`WARNING: API Server: aiohttp not installed → No adapter available`). No separate host configured. Contracts validated only vs docs + MockWebServer. |
| 5 | Validate web_fetch live URLs | ⛔ NOT VERIFIED | SSRF/redirect/extract covered by unit tests (MockWebServer); no Android device for real public-URL fetch from here |
| 6 | Combined integration (consent/boundaries) | ⚠️ PARTIAL | Build/tests green; runtime consent/permission boundary + DeepSeek/memory preservation not exercised on device |
| 7 | Integration branch + conflicts + CI | ✅ PASS | `integration/audit`, conflicts resolved, full test suite + emulator green |
| 8 | Consolidated report | ✅ PASS | this document |

---

## 5. Recommended merge order

Conflict surface is concentrated in `ChatScreen.kt` + `build.gradle.kts`. Least-rework order:

1. **#13 Google Workspace** first (largest `ChatScreen`/`GoogleAuthManager`/`MainActivity` rewrite).
2. **#12 Hermes** (adds engine selector + settings on top).
3. **#14 web_fetch** (adds one tool registration; trivial once #12/#13 in).
4. **#15 PostHog** last (small; only `NexusApplication` + analytics toggle + one `buildConfigField`).

*(#12/#14 are mine; #13/#15 are another agent's — merge only after each author confirms.)*

---

## 6. Security findings (pre-existing, from main audit)

- 🔴 **Direct Android→PostgreSQL** with on-device-decryptable Neon credentials + a broad `nexus` role
  (`docs/PRODUCTION-HARDENING.md`); the single biggest pre-release risk. Owner-gated.
- 🟠 **Gmail id_token `email` not signature-verified** (`GoogleAuthManager.emailFromIdToken`) — base64-decode
  only; display-only but a real crypto gap.
- 🟡 Dead/orphaned code: `KnowledgeApiClient`, `KnowledgeRepository`, inert `knowledgeBaseUrl` settings field
  (silent no-op), `McpBridge`, orphaned ViewModel search APIs, unwired action-contract verification.
- 🟡 `ChatViewModel` God-object + manual composition root; non-streaming primary agentic path.

None of the four PRs introduce a new secret-leak path; PostHog is opt-in (`optOut=true`, empty properties),
and Hermes/web_fetch keys stay encrypted + never logged.

---

## 7. Physical-device acceptance requirements (before any merge to main)

1. Hermes: a real host with `aiohttp`/API-server + a **read-only profile**; verify `request_id` vs `prompt_id`,
   SSE replay/reconnect, approval once/deny, cancel.
2. web_fetch: on-device fetch of 2–3 public URLs + an adversarial private-IP URL (confirm block).
3. Google Workspace: OAuth consent flow + read-only Gmail/Drive/Calendar/Contacts/Tasks on device; no write
   without approval.
4. PostHog: confirm **zero** events before opt-in, and correct region/host.
5. Regression: DeepSeek chat + memory recall + `memory_save` approval still work after integration.

---

## 8. GO / NO-GO

**NO-GO for release.** GO for continued development and preview builds. Unblock by (a) resolving the Neon
JDBC role exposure + Gmail JWT verification, (b) deleting/wiring the orphaned knowledge-base path so the
Settings field is honest, and (c) completing the device/live-server acceptance tests above.
