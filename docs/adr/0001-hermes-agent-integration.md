# ADR-0001 — Integrate Hermes Agent as an optional execution backend

- **Status:** Proposed (implementation on `feature/hermes-agent-backend`, not merged)
- **Date:** 2026-11-01
- **Deciders:** NEXUS owner

## Context

NEXUS Agent is an Android app whose chat is powered by DeepSeek (OpenAI-compatible) with a
local tool loop (`ToolLoop`/`ToolOrchestrator`) and a Second Brain knowledge base in Neon. Nous
Research's Hermes Agent is a self-improving, autonomous agent that runs its own tool loop
(terminal, files, web, memory, skills) server-side and exposes an OpenAI-compatible API server
plus a Runs API (`/v1/runs`, `/v1/runs/{id}/events`, `/v1/runs/{id}/approval`, `/v1/runs/{id}/stop`,
`/v1/capabilities`, `/health`, `/v1/chat/completions`).

We want Hermes as an **optional, autonomous agent-execution backend**, not a replacement for
DeepSeek, and not a merge into `main` until an owner approves.

## Decision

1. **Separate backend, not a replacement.** DeepSeek and its tool loop are unchanged. A new
   `HermesRemoteClient` (OkHttp + kotlinx-serialization, manual SSE parsing) talks to a
   user-configured Hermes API server. The engine is selected in Settings
   (`DeepSeek` / `Hermes (Beta)`).

2. **Runs API over Chat Completions.** The Hermes path uses the Runs API (create → SSE events →
   terminal), because it is the streaming-friendly surface that exposes tool lifecycle and
   approval events, which the NEXUS UI needs to render progress and approval cards. `/v1/chat/completions`
   is supported by the client for completeness/diagnostics only.

3. **Configurable endpoint + encrypted bearer key, no embedded privileged key.** The endpoint is a
   non-secret preference; the API-server key is stored AES-GCM-encrypted in `SecretStorageRepository`
   (same path as the DeepSeek key). Nothing privileged is baked into the APK.

4. **TLS-by-default.** The endpoint must be `https` for any non-loopback host (plain `http` is
   permitted only for loopback local dev). `HermesSafety.normalizeEndpoint` enforces this and
   normalizes to an origin.

5. **Hermes is disabled until configured and verified.** The engine option is gated on a
   `HermesConnectionState.Ready` (a `capabilities` + `health` round-trip that also checks the Runs
   API is advertised). Sending without a ready backend fails closed with a clear error.

6. **Approvals bridged, not assumed.** Hermes tools execute on the Hermes host, in a separate
   environment. NEXUS's local `ApprovalGate` does **not** protect them. Instead, the client maps
   the `approval.request` SSE event (and `pending_controls` from the status endpoint) to the same
   approval-card UI and resolves it via `POST /v1/runs/{id}/approval` with `choice: once|deny`.
   A control with no id fails closed (run stopped).

7. **Memory ownership.** NEXUS Neon remains the single source of truth for personal memory. The
   Hermes path sends only the user text plus the local Room user/assistant transcript
   (`conversation_history`); it does **not** inject memory recall, Second Brain, or Gmail content.
   Hermes's own long-term memory is scoped per-conversation via a stable `session_id`
   (`nexus-android-<conversationId>`). The final answer is persisted to Room (and mirrors to Neon
   only under the existing opt-in `mirror_enabled` gate). Hermes internal memory is never merged
   into Neon automatically.

8. **No duplicated/nested tool loop.** Under Hermes, NEXUS's `ToolLoop` is not invoked; the remote
   agent runs its own loop. Local safety gates (cognitive load, safety policy, "remember") still
   run before the message is handed off.

## Alternatives considered

- **Reuse the OpenAI `/v1/chat/completions` path via `AiModelRouter`.** Rejected: it is stateless
  and does not expose run lifecycle/approval/tool events in a way the NEXUS UI can render, and it
  would incorrectly funnel Hermes through NEXUS's local `ToolLoop` semantics.
- **Embed the Hermes Agent as a local process.** Rejected: the Termux package is not embeddable in
  an APK, and the directive requires a remote/separate host.
- **Add Hermes as a `ModelClient` in `AiModelRouter`.** Rejected: the router is a model-id→text
  abstraction without run lifecycle; Hermes needs a dedicated execution path.

## Consequences

- **Positive:** autonomous mode with live tool progress + approval UX; DeepSeek untouched;
  read-only-first safety posture; encrypted key; HTTPS enforcement; audit log without secrets.
- **Negative:** a second, parallel execution path in `ChatViewModel`; the client cannot enforce
  tool restrictions server-side (a read-only Hermes profile must be configured on the host — see
  `docs/hermes-agent-deployment.md`); no end-to-end verification without a real Hermes server and
  a physical device.

## References

- Hermes Agent API server docs (`website/docs/user-guide/features/api-server.md`)
- Hermes programmatic integration (`website/docs/developer-guide/programmatic-integration.md`)
- NEXUS `docs/hermes-agent-deployment.md`, `docs/hermes-agent-readiness.md`
