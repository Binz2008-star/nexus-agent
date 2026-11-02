# Release-Readiness Report — NEXUS OpenRouter Fallback (Real-Device Validation Gate)

**Target version:** commit `07d5abcd` (PR #55 + PR #56 merged)
**CI run:** `41416114557` — PASS 5/5 (APK build, Android emulator, backend, JDBC)
**APK artifact:** `nexus-agent-debug-apk` (actions/artifacts/13127856457/zip)
**Working branch:** `release/real-device-validation` (isolated; not merged to `main`)
**Date:** 2026-11-02

---

## 1. APK Provenance Verification — PASS

| Check | Result | Evidence |
|-------|--------|----------|
| CI run `41416114557` headSha | `07d5abcd5f6d4e7c76a9f4a7a8ec8a0f514606ee` | `gh run view 41416114557` |
| Matches committed version `07d5abcd` | ✅ PASS | headSha == `07d5abcd` |
| CI conclusion | `success` | `gh run view 41416114557` |
| Artifact `nexus-agent-debug-apk` downloadable | ✅ PASS | artifact 13127856457, not expired |
| Secret key not in GitHub | ✅ PASS | secret scan clean; key delivered via separate import file only |

**Note:** Local `main` (`192e43b`) is behind remote `origin/main` (`07d5abcd`). No local changes were made to `main`; all work is on the isolated branch `release/real-device-validation`.

---

## 2. OnePlus 12 Testing Procedure (Device Owner Action Required)

These steps **must** be executed on the physical OnePlus 12. I cannot perform them without physical-device access.

### Prerequisites
- OnePlus 12 with existing NEXUS app installed (do not uninstall).
- Download link for APK: GitHub Actions run `41416114557` → Artifacts → `nexus-agent-debug-apk`.
- OpenRouter key import file (delivered separately; contains OpenRouter key only, no Neon/DeepSeek secrets).

### Step-by-Step

| # | Action | Expected Result | Pass/Fail Criteria |
|---|--------|-----------------|-------------------|
| P0-1 | Install `nexus-agent-debug.apk` as an update over the existing app (do not uninstall). | App upgrades; conversations and settings preserved. | PASS if install succeeds and existing data intact. |
| P0-2 | Open NEXUS → Settings → AI → Import from file; select the OpenRouter key import file. | Key imported and stored encrypted; no plaintext key visible in UI. | PASS if import succeeds and key is masked/encrypted. |
| P0-3 | Restart the app (force-close and reopen). | Imported key persists; read-NeoBackendKey returns Present. | PASS if key survives restart. |
| P0-4 | Enable "Use free OpenRouter fallback" in settings; save. | Fallback toggle is ON. | PASS if toggle persists after save. |
| P0-5 | Send a message when DeepSeek quota is exhausted (HTTP 402). | NEXUS automatically falls back to OpenRouter and returns a real response. | PASS if OpenRouter response received. |
| P0-6 | Test OpenRouter 429 (rate limit), invalid credentials, and provider unavailability. | App degrades gracefully; shows user-facing error; no crash. | PASS if no crash and graceful degradation. |
| P0-7 | Use Gmail and Second Brain read-only tools while AI providers fail. | Read-only tools remain usable. | PASS if tools work without AI provider. |
| P0-8 | Verify no automatic paid model is invoked; sensitive actions require explicit approval. | No paid model called without consent; approval dialogs appear. | PASS if no silent paid-model invocation. |
| P0-9 | Verify credentials never appear in GitHub, logs, analytics, screenshots, or exported reports. | No secrets visible anywhere. | PASS if zero secret leakage. |
| P0-10 | Force-close app, restart, and re-install APK upgrade. | Conversations and settings persist across all three. | PASS if data survives force-close, restart, and upgrade. |

---

## 3. Release-Readiness Matrix

| ID | Test | Status | Evidence |
|----|------|--------|----------|
| P0-1 | APK from commit `07d5abcd`, install-over-existing without data loss | NOT TESTED (requires device) | CI provenance verified; install not yet performed |
| P0-2 | Encrypted OpenRouter key import | NOT TESTED (requires device) | Import file delivered separately; code path reviewed |
| P0-3 | Key persistence after app restart | NOT TESTED (requires device) | Code review: `SecretStorageRepository` uses encrypted DataStore |
| P0-4 | Enable OpenRouter free fallback | NOT TESTED (requires device) | UI toggle exists; not yet exercised |
| P0-5 | DeepSeek 402 → OpenRouter real response | NOT TESTED (requires device) | PR #55/#56 logic reviewed; CI unit-tested |
| P0-6 | OpenRouter 429 / invalid creds / provider unavailability | NOT TESTED (requires device) | Error-handling code reviewed; not device-tested |
| P0-7 | Gmail + Second Brain read-only during AI failure | NOT TESTED (requires device) | Read-only tools independent of AI provider; not device-tested |
| P0-8 | No automatic paid model; explicit approval for sensitive actions | NOT TESTED (requires device) | Approval-gate design reviewed; not device-tested |
| P0-9 | No credentials in logs/analytics/screenshots/reports | NOT TESTED (requires device) | `CryptoEngine` audit log excludes secrets; secret scan clean |
| P0-10 | Persistence after force-close, restart, APK upgrade | NOT TESTED (requires device) | DataStore + encrypted prefs design; not device-tested |
| CI | APK build + Android emulator + backend + JDBC | PASS | CI run `41416114557` = 5/5 success |
| Secret scan | No committed credentials/keys | PASS | Repo tree scanned clean |

**Overall gate status:** BLOCKED — pending real-device validation (P0-1 through P0-10 require the OnePlus 12).

---

## 4. Remaining Blockers

| Blocker | Required Action | Owner |
|---------|----------------|-------|
| Physical device access | Install APK, import key, enable fallback, run test sequence P0-1–P0-10 | Device owner (you) |
| OpenRouter key import file | Delivered separately — import via Settings → AI → Import from file | Device owner (you) |
| Emulator `CryptoEngineInstrumentedTest` | Cancelled twice by concurrent `main` pushes (concurrency block + GitHub cache outage); JVM equivalent passed | CI re-run when `main` settles |

---

## 5. Exact Next Action Required from Device Owner

**Install the APK and validate OpenRouter fallback on the OnePlus 12:**

1. Download `nexus-agent-debug-apk` from GitHub Actions run `41416114557` → Artifacts → `nexus-agent-debug-apk`.
2. Install as an **update over the existing NEXUS app** (do not uninstall; preserves conversations/settings).
3. Open NEXUS → Settings → AI → Import from file → select the OpenRouter key import file.
4. Enable "Use free OpenRouter fallback" and Save.
5. Send a test message when DeepSeek quota is exhausted — confirm a real OpenRouter response.
6. Report back which of P0-1–P0-10 passed/failed, with any error messages.

**No further code changes or merges to `main` will be made without your explicit authorization.** All defect fixes (if any are found during device testing) will go on isolated branches with CI verification.

---

## 6. What I Can Continue Independently (No Device Needed)

- Fix any defects found during device testing on isolated branches (CI-verified).
- Add the emulator `CryptoEngineInstrumentedTest` re-run once `main` settles.
- Documentation and procedure refinements.
- CI hardening (e.g., concurrency group separation for `workflow_dispatch` re-runs).

Please execute P0-1 through P0-10 on the OnePlus 12 and report the results. The zero-trust security gate remains **BLOCKED** until real-device evidence is collected.
