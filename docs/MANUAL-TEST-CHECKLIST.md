# NEXUS Agent — Manual end-to-end test checklist

This is the **manual** verification checklist. The automated suite (CI `build` +
`instrumentation-tests`) is green, but these steps require a physical device with a live
DeepSeek key, a registered Google OAuth client id, and real Neon connection URLs. **None of these
have been executed** (no device is attached to the build host); items below are marked
`[ ] not run` until completed on-device.

## Prerequisites

1. Install the latest debug APK (commit `5b0663f`, run `41253616969`):
   - Artifact: `nexus-agent-debug-apk` → `app-debug.apk`
   - Web link: https://github.com/Binz2008-star/nexus-agent/actions/runs/41253616969/artifacts/13062636468
   - `adb install app-debug.apk` (minSdk 26 / Android 8.0+)
2. Settings → **Import from file** → pick the rotated `nexus-neon-rotation.txt`
   (fills DeepSeek key + Neon read/write URLs) → **Save**. Values are stored encrypted.
3. (Gmail) A Google OAuth client id must be present in the build (`GOOGLE_OAUTH_CLIENT_ID`) —
   see [`docs/OAUTH-SETUP.md`](OAUTH-SETUP.md) for the exact Google Cloud configuration.

### Signing (stable)

CI builds are now signed with a pinned **test** certificate (`CN=Nexus Test Signing`, SHA-1
`15:88:D3:C3:46:01:62:71:66:76:5C:05:64:20:79:04:BD:CB:40:34`), so **subsequent** builds share one
identity and `adb install -r` upgrades work in place. The one-time exception: an APK installed
from *before* this change was signed with a different (ephemeral debug) key, so that first upgrade
requires `adb uninstall com.nexus.agent` (clears on-device Room history; the optional Neon mirror
is unaffected). After that first upgrade, no uninstalls are needed.

## 1. DeepSeek live chat + function calling

- [ ] Send "من أنت؟" → model answers as **NEXUS Agent** (identity prompt present).
- [ ] Ask a current-events question → model proposes `web_search`, cites sources.
- [ ] Ask "ابحث في ذاكرتي عن …" → model proposes `second_brain_search`.
- [ ] Ask a personal question that triggers `memory_save` → an **approval card** appears.

## 2. Gmail OAuth + authorized inbox search

- [ ] Settings → **Connect** Gmail → Custom Tab opens → authorize the `gmail.readonly` scope.
- [ ] Send "تفقد بريدي الإلكتروني" (Email command) → returns matching messages.
- [ ] Ask the same thing through the **generic** AI path → `gmail_search` runs (scopes forwarded).
- [ ] Disconnect Gmail → the same request is **blocked** (unauthorized), no crash.

## 3. Neon Second Brain search

- [ ] With the read URL set, ask a knowledge question → Second Brain (`chunks_v4`/`memory`) hits.
- [ ] Remove/blank the read URL → search degrades to "no sources", no crash.

## 4. Personal memory save / restart / recall

- [ ] Approve a `memory_save` (or use the "remember …" command) → "تم حفظها في ذاكرتي".
- [ ] Force-stop the app and relaunch (new conversation).
- [ ] Ask a related question → the saved fact is recalled (fenced `<memories>` context).

## 5. Arabic & English memory recall

- [ ] Save an English fact; ask a **differently-worded** English question (e.g. "preferred colour"
      vs "favorite color") → recalled.
- [ ] Save an Arabic fact; ask a reworded Arabic question → recalled.

## 6. Approval / rejection of memory writes

- [ ] When the model proposes `memory_save`, tap **Approve** → fact saved.
- [ ] Propose again and tap **Reject** → fact NOT saved, loop continues.
- [ ] The same approved action cannot be replayed (one-shot receipt).

## 7. Network failures / invalid credentials / recovery

- [ ] Airplane mode → chat shows a network error, no crash; restore → recovers.
- [ ] Enter an invalid DeepSeek key → `auth`/`401` error surfaced (not a raw body).
- [ ] Enter an invalid Neon URL/password → `no_connection`/`auth`, no crash, Second Brain skipped.

## Status

- [ ] **All items unverified on-device** — this list is the acceptance test for release validation.
