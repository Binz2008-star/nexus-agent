# NEXUS Agent — Architecture

This document describes the system as actually implemented (commit `6a78b10` baseline,
post-integration-repair). It supersedes any earlier roadmap text that described tool-calling or
account integration as "not planned".

## Overview

NEXUS Agent is a single-user Android chat app. The model (DeepSeek) can *propose* tool calls; a
local policy layer validates them; COMMIT-level mutations require explicit user approval; tool
output is fenced as untrusted data before being fed back to the model.

The pipeline for a normal (non-command) message:

```
ChatScreen → ChatViewModel.sendMessageWithAgent()
  ├─ cognitive-load gate (spam)
  ├─ safety policy (block / harmful-intent / sensitive-topic)
  ├─ "remember …" command → saveMemory (personal memory)
  ├─ command routing (poetry / breathing / email / greeting / agentic)
  └─ launchModelDrivenTask()
       ├─ conversationHistory (raw user/assistant turns)
       ├─ recallMemoryContext(query)   → fenced personal memories
       └─ ToolLoop.run(history+memories, model, schemas, propose, grantedScopes)
            └─ propose → ChatRepository.completeWithTools()  (injects system prompts)
                 └─ DeepSeek /chat/completions (tools + tool_choice=auto)
```

## Tool-calling system

- **`Tool`** (`domain/tool/Tool.kt`) — capability contract: `name`, `description`,
  `riskLevel` (READ/ANALYZE/PREPARE/COMMIT), `requiredScopes`, `parameterSchema`,
  `execute(JsonObject)`.
- **`ToolOrchestrator`** (`domain/tool/ToolOrchestrator.kt`) — the only execution path. Enforces,
  in order: blocked-tool deny-list → unknown-tool → required-scope check → COMMIT approval
  receipt (bound to tool + arguments, one-shot). Returns `ToolResult` (`Success`/`Failure`/`Blocked`).
- **`ToolLoop`** (`domain/orchestration/ToolLoop.kt`) — the model-driven loop: model proposes
  (answer or tool calls) → orchestrator validates/executes → fenced results fed back → repeat up
  to `maxRounds`. COMMIT tools pause on an injectable `requestApproval` callback.
- **`ChatViewModel.launchModelDrivenTask`** wires the loop, supplies `grantedScopes` (from the
  connected Gmail connector) and a scripted web + Second-Brain fallback when the loop fails.

Registered tools: `web_search`, `second_brain_search`, `gmail_search`, `memory_save`.

### Approval / consent (COMMIT tools)

`ApprovalGate` issues receipts bound to the exact tool name + serialized arguments. The ViewModel
shows an `ApprovalCard`; a `CompletableDeferred` suspends the loop until the user approves or
rejects. Missing/rejected approval = default-deny. A receipt is consumed once and cannot be
replayed. `memory_save` is the first COMMIT tool.

### Prompt-injection defense

External data (web excerpts, email, knowledge chunks, personal memories) is wrapped in explicit
"untrusted data" fences (`<references>`, `<tool_results>`, `<memories>`) — carried in a
system-role message but explicitly marked untrusted so it is not treated as directives. Tool
results are re-fenced on every loop round, and memory content is delimiter-escaped + length-capped.

## Neon (Postgres) integration

Two logical stores, two roles:

| Database | Role | Tables | Used for |
|---|---|---|---|
| `personal-ai-core` | `nexus` (write) | `nexus_memory`, `nexus_conversations` | personal memory save/recall, conversation mirror |
| `second-brain` | `nexus_reader` (read) | `chunks_v4`, `memory` | Second Brain knowledge search |

`NeonRepository` (`data/repository/NeonRepository.kt`) is the single access point:

- `search(query, topK)` — read-only Second Brain search via the **read** URL (`nexus_reader`).
- `saveMemory` / `loadMemory` / `recallMemory` — personal memory via the **write** URL (`nexus`).
- `saveConversationMessage` / `mirror` — conversation mirror (opt-in via `mirror_enabled`).

SQL lives in `SecondBrainSql` (pure, unit-testable); row mapping and error mapping are pure too.
All JDBC I/O runs on `Dispatchers.IO` and degrades to a failure result on any outage (never
throws into the chat flow). Connection strings are user-supplied, encrypted at rest, and never
embedded in the APK.

### Personal-memory recall

`NeonRepository.recallMemory(query, limit)` fetches a bounded recent window from
`personal-ai-core.nexus_memory` (reusing `loadMemory`), ranks relevance with `MemoryRecall`, and
the ViewModel injects a fenced `<memories>` context. This is **independent** of Second Brain
search: saving and recalling a fact use `nexus_memory`, not `chunks_v4`/`memory`.

`MemoryRecall` relevance is a local, language-aware fuzzy match (no embeddings, no extra API
calls): Unicode normalization (NFKC + lowercase + Arabic diacritic stripping + letter
unification), stopword removal, a small synonym lexicon (favorite/preferred, color/colour,
job/work, …), and exact → prefix → character-bigram similarity scoring.

## Gmail connector

`GoogleAuthManager` owns the OAuth 2.0 (PKCE) lifecycle: Custom-Tabs authorization, code exchange,
encrypted refresh-token storage, on-demand access-token refresh. `GmailConnector` declares the
read-only scope (`…/auth/gmail.readonly`). `GmailSearchTool` requires that scope; the orchestrator
blocks execution when it is not in the granted-scope set. `ChatViewModel.launchModelDrivenTask`
passes `gmailScopesProvider()` (the connected connector's scopes) into `ToolLoop`, so authorized
read-only search works through the normal AI path while unauthorized access stays blocked.

## Context construction

`ChatRepository.buildHistoryWithSystem` injects, in order: name-safety prompt → NEXUS identity →
custom system prompt (Settings) → persona/dialect instruction → (optional) grounding context.
`completeWithContext` and `completeWithTools` both use it, so normal chat and model-driven tool
execution carry identical instructions.

## Security model (summary)

- DeepSeek key + Neon URLs + Google refresh token stored as AES-256-GCM ciphertext (Android
  Keystore, non-exportable). Plaintext never written to disk.
- Neon/Google credentials are never embedded in the APK or CI, and never logged. JDBC URLs are
  masked (`password=••••••`) for display; only `sslmode=require/verify-ca/verify-full` accepted.
- Single-user app; no cross-user data path. Transcript mirror is opt-in and off by default.

## Remaining limitations

- Personal-memory recall uses lexical fuzzy matching (normalization + synonym lexicon + prefix +
  character-bigram). It does not use embeddings, so a true paraphrase that shares no surface form
  or listed synonym will not match. Cross-language recall is not supported.
- Direct Android→PostgreSQL JDBC; a backend API boundary is proposed (see
  `docs/PRODUCTION-HARDENING.md`).
- OAuth is single-account per connector set; a real Google client id + on-device redirect test is
  still pending.
- In-flight requests do not survive process death.
