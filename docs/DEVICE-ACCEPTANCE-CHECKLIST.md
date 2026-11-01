# Physical-Device Acceptance Checklist

Install `~/storage/downloads/NEXUS-agent.apk` (signed `CN=Nexus Test Signing`, in-place-upgrade
compatible with the previous test build). Do **not** uninstall first unless the previous install was
signed with a different key — uninstalling wipes local chat data (mirror is opt-in).

## 0. Prerequisites
- [ ] Android 8.0+ (minSdk 26).
- [ ] DeepSeek API key + Neon read/write connection strings available (Settings → **Import from file** → `nexus-neon-rotation.txt`), or entered manually.
- [ ] Google Cloud OAuth client configured with package `com.nexus.agent` + the signing cert SHA-1 (`15:88:D3:C3:46:01:62:71:66:76:5C:05:64:20:79:04:BD:CB:40:34`) + `GOOGLE_OAUTH_CLIENT_ID` set.

## 1. Google OAuth + Gmail
- [ ] Settings → **Connect** (Gmail) opens the Google consent screen (no blank-client error).
- [ ] Grant Gmail **Read** scope → the service shows `✓ Gmail · قراءة`.
- [ ] Gmail **Send** scope remains OFF until granted (approval-gated).
- [ ] Post-auth, the token is stored encrypted (no plaintext in Settings/logs).

## 2. DeepSeek chat
- [ ] Send a message → streaming reply renders (Arabic + English).
- [ ] Second message keeps context (Room conversation persists across app restarts).

## 3. Second Brain search
- [ ] Ask a question matching a `chunks_v4`/`memory` row → cited source appears (project/file).
- [ ] Arabic query returns Arabic content; English query returns English.

## 4. Persistent memory (Neon)
- [ ] `memory_save` (fact) works only after the approval prompt (default-denied).
- [ ] The saved memory is recallable in a later conversation (persistent, not session-only).

## 5. Approval-gated send (Gmail)
- [ ] Sending an email triggers a confirmation card; rejecting sends nothing.
- [ ] No email is sent without explicit approval.

## 6. Arabic/English
- [ ] UI strings + responses in both languages; RTL layout renders correctly.

## 7. Signing / upgrade
- [ ] Install over the previous build without uninstall (data preserved).
- [ ] `Settings → Import from file` does not overwrite an existing stored key unless edited.

## Sign-off
- [ ] All items pass → device acceptance APPROVED → eligible to flip `use_neon_backend` and remove JDBC.
- [ ] Any item fails → record the exact failure + repro, do not flip the flag.
