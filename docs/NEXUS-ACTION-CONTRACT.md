# NEXUS action contract / execution-verification slice

Status: **draft / not enabled for ordinary NEXUS chats**. This change ports a small,
testable control from `personal-ai-core/docs/ADR/ADR-023-plan-execute-verify.md`.
It is not the complete Personal AI Core agent, and it does not add a planner or new tools.

## What is enforced

- A **trusted caller** may set `AgentTaskContract(actionRequired = true)`.
  The model cannot set, downgrade, or satisfy the contract by saying an action occurred.
- A final answer without successful tool execution is rejected, counting against the
  same three-failure budget. A limit of 12 proposed actions prevents unbounded tool calls.
- `ToolResult.Success` is recorded as **EXECUTED**, not independently verified.
- A caller that needs an independently checked postcondition must set
  `independentVerificationRequired = true` **and** supply an explicit
  `independentlyVerify(call, result)` callback. With no callback, verification fails closed.
- Minimal `ActionEvidence` records contain tool name and status only. They intentionally
  exclude arguments and external tool outputs, which could contain private data.
- Existing `ToolOrchestrator` scope checks, approval receipts, one-shot consumption,
  blocked tool list, and untrusted-results fencing are unchanged.

## What this does NOT prove

A successful tool invocation does **not** prove a real-world external state change.
Do not label an action task "completed and verified" unless a trusted postcondition
has actually been checked. Free-form answer text is still not a structured claim;
adding verified `file_created`, `tests_passed`, `sent`, etc. claims is a follow-on
unit of work requiring caller-specific evidence providers.

The default `AgentTaskContract()` leaves existing normal chat behavior unchanged.
The Android UI does **not** yet select a contract mode, and no live-device E2E has run.
Do not turn on `actionRequired` based on untrusted model output, retrieved documents,
or an unreviewed heuristic of user text.

## Next steps

1. Add an explicit UI task mode / trusted action intent and forward its typed
   `AgentTaskContract` into `ToolLoop.run`; no inference from tool result text.
2. Build tool-specific, testable postcondition callbacks for appropriate *read-only*
   operations first. Do not create a generic "always true" verifier.
3. Add structured completion claims only after there is independent evidence.
4. Verify behavior on a connected Android device and reconcile with PR #1 before merge.
5. Keep PR #2 Mashaaer changes on their separate branch.

## Source handoffs reviewed

- `Binz2008-star/termux-config/handoffs/INDEX.md` (2026-10-28)
- `.../handoffs/2026-10-28-phone-agent.md` (least privilege, explicit approvals)
- `.../TASKS.md` (owner actions and no unverified completion claims)
- `Binz2008-star/personal-ai-core/docs/ADR/ADR-023-plan-execute-verify.md`
- `Binz2008-star/personal-ai-core/docs/PROJECT_INTEGRATION.md`

Changes are GitHub-only; no Termux runtime, Neon data, secrets or existing APK is modified.
