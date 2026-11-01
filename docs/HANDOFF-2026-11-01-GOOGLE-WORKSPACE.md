# NEXUS — Google Workspace integration handoff (2026-11-01)

**PR:** https://github.com/Binz2008-star/nexus-agent/pull/13
**Branch:** `feat/gmail-google-identity-authorization`
**Base:** `main` at `c21bd78b21b2f1cd52682b6d9ca8bc95704be568`
**Owner requirement:** NEXUS is their personal agent. Use one Android Google OAuth client
for multiple Workspace APIs while requesting least-privilege scopes per capability.
Read Gmail, send email after approval, read Drive, work with app-managed files, and
expand to more Google services over time.

## Sources inspected
- `docs/OAUTH-SETUP.md`, existing AppAuth `GoogleAuthManager.kt`, `MainActivity.kt`,
  `SecretStorageRepository.kt`, `GoogleConnector.kt`, `ChatScreen.kt`.
- `ToolLoop.kt`, `ToolOrchestrator.kt`, `GmailTool.kt`, `MemorySaveTool.kt`,
  Gmail API client and scope rules.
- Official Google Android AuthorizationClient and Google OAuth scopes documentation.

## Work done on this branch
- Move Android consent to Google Play Services `AuthorizationClient` and ActivityResult,
  remove custom-scheme redirect intent filters and AppAuth browser flow.
- Do not store access or refresh tokens in NEXUS persistence; delete legacy encrypted
  OAuth tokens when disconnecting; require explicit user consent per service.
- Grant metadata lives only in the running Android process. Restart may require
  reconnect; no headless re-consent. Validate same Google account identity.
- Add Gmail send (COMMIT + one-shot user approval, recipient/header validation, single
  attempt), Drive read search (READ) and create empty Docs/Sheets/Slides (COMMIT).
- Add bounded Calendar event, Contacts list and Tasks list read-only capabilities.
- Register tools with existing NEXUS approval policy and Google scope gate; do not create
  a second approval system or bypass blocked tools.
- Add scope, MIME header injection, tool approval and host-allowlist tests.
- Documentation `docs/OAUTH-SETUP.md` records APIs, scopes, testing and rollout limits.

## Required release validation
1. Check GitHub Actions **against the exact current PR head**, including build and
   emulator tests; repair any failures on this branch.
2. Review for untrusted Google API responses, avoid arbitrary token-bearing URL requests,
   verify no credentials are accidentally committed, and address Codex findings.
3. Owner must enable Gmail/Drive/Calendar/People/Tasks APIs in Google Cloud, add
   scopes and Test users, and ensure correct Android package name and signing SHA-1.
4. Physical device tests: incremental authorization, denied scopes, Gmail search,
   Gmail send (approve and deny), Drive search and new-file creation, Calendar upcoming,
   Contacts and Tasks lists, disconnect, account switch, restart.
5. Confirm external account send cannot occur without an approval receipt and
   network failure cannot silently retry COMMIT.
6. No full-drive editing, existing-file write/delete, Gmail delete, calendar/task
   mutation or general Google-wide grant is part of this PR. Add future integrations
   separately with tests and explicit scopes.

## Remaining work
- Code review/CI result must be recorded when completed.
- Google Cloud configuration and live-account acceptance cannot be proven by GitHub CI.
- Production needs Google restricted-scope verification as applicable, app data handling
  assessment, backend architecture for production-grade sessions, and durable task
  idempotency/recovery where required.
- Expand Sheets/Docs/Slides **content editing**, Calendar/Tasks mutation, Forms, Chat,
  Meet, Photos, YouTube and other available APIs as independently tested modules.
- Keep PR draft; no merge to `main` without reviewed CI + explicit owner approval.
