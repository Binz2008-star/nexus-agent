# OpenRouter free-model fallback — NEXUS Android

**Status:** implementation branch, NOT a production release. No live API key or physical-device test is claimed.

## Purpose
NEXUS should remain usable when DeepSeek returns HTTP 402 (insufficient credits), 429, 500/502/503/504, or there is no DeepSeek key.

## Design

- Primary provider: existing DeepSeek transport.
- Secondary provider: `https://openrouter.ai/api/v1/chat/completions`; only `model=openrouter/free`.
- API key: user-supplied in Android settings; stored encrypted with existing Android Keystore AES-GCM DataStore infrastructure, in a distinct secret slot. No hardcoded key, no BuildConfig secrets, no console/analytics key logs.
- Explicit opt-in toggle (off by default). If enabled and the key exists, eligible DeepSeek errors cause **one** OpenRouter free-model call. On OpenRouter 429/other failures, stop; never loop or silently use a paid model.
- The existing tool proposal validation, read-only scopes and per-COMMIT approval gate remain unchanged: the provider may **propose** actions but cannot execute tools itself.
- Do not switch providers after any streaming text fragment has been emitted, avoiding ambiguous duplication.
- The optional provider can receive user text, history and tool context, including retrieved email/source excerpts when the user explicitly enables fallback. Users must consider this data-sharing before opt-in.
- Disconnect: settings > AI > Remove saved OpenRouter key; also disables fallback.
- An app restart retains preferences/secrets; no application data migration is required.

## Free tier and external dependencies
The free model router and current free-model rate limits are documented at:
- https://openrouter.ai/collections/free-models
- https://openrouter.ai/pricing

The published free cap is typically 50 requests per day and 20/minute (failed attempts may consume quota); capacity, model selection, tool support and data-handling policies can change. **This does not promise unlimited free inference or an SLA**.

## Release gates

- Android JVM unit tests and emulator instrumentation tests pass on exact PR HEAD.
- Manual physical-device test: DeepSeek HTTP 402 with valid OpenRouter key and opt-in shows a real completion.
- Disable fallback and prove no request to OpenRouter.
- Test OpenRouter 429, wrong key, zero credits and no available free model: app stays responsive, message persistence works; no paid request.
- Test search/knowledge and Gmail read as independent functions when BOTH model providers are unavailable.
- Test Gmail send one-shot approval with forced failure before and after proposal; prove no duplicate send.
- Verify providers' data-retention/privacy settings before enabling for personal mail or Second Brain context.
- No release or merge to main without owner approval.

## Relationship to PR #55
PR #55 separately adds degraded read-only operation (returning real Gmail/Second Brain results when model synthesis fails). This branch integrates an optional genuine free inference fallback **independently of PR #55**. Both should be validated as a combined release candidate, especially the error handling path and transcript restoration.
