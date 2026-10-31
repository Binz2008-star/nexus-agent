# NEXUS Agent

A standalone Android AI assistant built with Kotlin and Jetpack Compose. It is a
private-by-default chat app backed by your own [DeepSeek](https://platform.deepseek.com)
API key — no Termux, no third-party backend, no bundled credentials.

> Status: **in development**. Chat, streaming, persistence, encrypted key storage, tool-calling,
> Neon (Postgres) integration, personal-memory recall, and the Gmail connector are implemented
> and CI-verified. Not yet published to any store.

## Features

- **Chat with DeepSeek** — `deepseek-v4-pro` and `deepseek-flash` models, streaming
  (token-by-token) responses.
- **Model-driven tool calling** — the model proposes tools; a local policy layer validates them;
  COMMIT-level actions require explicit user approval (function calling + an approval card).
- **Personal memory** — save approved facts and recall them into later conversations (bounded,
  relevance-ranked, fenced as untrusted data).
- **Second Brain search** — read-only knowledge search over Neon Postgres (`chunks_v4` + `memory`)
  with trigram-ranked matching.
- **Gmail connector** — read-only inbox search (OAuth 2.0 + PKCE); unauthorized access stays
  blocked.
- **Local history** — conversations persisted on-device with Room; optional opt-in Neon mirror.
- **Encrypted secrets** — DeepSeek key, Neon URLs, and the Google refresh token stored as
  AES-256-GCM ciphertext in the Android Keystore; plaintext is never written to disk.
- **Arabic & English** — full string resources with RTL support; follows the system locale.
- **Light & dark theme** — follows the system setting.
- **Minimal permissions** — only `INTERNET`.

## Architecture

```
com.nexus.agent
├── ui/            Compose screens + ViewModels (ChatScreen, ChatViewModel) + theme
├── domain/
│   ├── model/     ChatMessage, GmailMessage, KnowledgeResult, WebSearchResult
│   ├── tool/      Tool contract, ToolOrchestrator, ToolSchema, per-tool impls
│   ├── orchestration/  ToolLoop (model-driven propose→validate→execute→fence→answer)
│   ├── policy/    ApprovalGate (COMMIT consent receipts)
│   ├── neon/      SecondBrainSql, NeonConnection(Parser), CredentialFileParser, MemoryRecall
│   ├── connectors/  Connector, GmailConnector, GoogleConnector, PendingOAuthSession
│   ├── safety/    SafetyPolicy, IntentEvaluator, UserBehaviorGuard, SensitiveTopicDetector
│   ├── router/    AiModelRouter (provider selection)
│   └── …          persona, emotion, cognitive, culture, memory, commands, wellness
├── data/
│   ├── network/   DeepSeekClient (OkHttp + kotlinx.serialization, SSE + function calling)
│   ├── local/     Room (MessageEntity, MessageDao, ConversationDao, NexusDatabase)
│   ├── remote/    NeonJdbcClient (pgjdbc), GmailApiClient, WebSearchApiClient
│   ├── connectors/ GoogleAuthManager (AppAuth PKCE, encrypted refresh token)
│   └── repository/ ChatRepository, NeonRepository, GmailRepository, WebSearchRepository,
│                   SecretStorageRepository, AppPreferencesRepository
└── security/      Android Keystore AES-GCM (CryptoEngine, AesGcmCipher, …)
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full pipeline (tool loop, Neon
two-store layout, memory recall, Gmail, and context construction) and
[`docs/PRODUCTION-HARDENING.md`](docs/PRODUCTION-HARDENING.md) for the security review and
backend-API/role-permission/branch-protection proposals.

## Tech stack

| Component | Version |
|---|---|
| Kotlin | 1.9.24 |
| Android Gradle Plugin | 8.5.2 |
| Gradle wrapper | 8.7 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |
| Jetpack Compose | BOM 2024.06.00, Material 3 |
| Room | 2.6.1 (KSP 1.9.24-1.0.20) |
| OkHttp | 4.12.0 |
| kotlinx.serialization | 1.6.3 |
| DataStore (preferences) | 1.1.1 |

## Building

Requires JDK 17 and the Android SDK (platform 34). The Gradle wrapper downloads Gradle 8.7
and verifies its checksum automatically.

```bash
./gradlew assembleDebug              # debug APK
./gradlew testDebugUnitTest          # JVM unit tests
./gradlew connectedDebugAndroidTest  # instrumentation tests (device/emulator)
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### CI

GitHub Actions (`.github/workflows/build.yml`) builds the debug APK and runs both the JVM
unit tests and the API 34 emulator instrumentation tests on every push/PR to `main`, with a
least-privilege `contents: read` permission. The debug APK is uploaded as a workflow
artifact (14-day retention).

## Setup

1. Install the debug APK (or build it yourself).
2. Create an API key at <https://platform.deepseek.com/api_keys>.
3. Open the app → **Settings** → paste the key (or **Import from file** to load the DeepSeek
   key and the Neon read/write URLs in one step) → **Save**.
4. Optionally connect Gmail (requires a registered Google OAuth client id) and enable the
   Neon Second Brain / memory URLs.

Secrets are stored encrypted on-device. **Never commit a key or a Neon URL** — there is no
bundled or default credential, and no credential is ever logged or embedded in the APK.

## Security model

- The API key, Neon connection URLs, and the Google refresh token are encrypted with
  AES-256-GCM using a **non-exportable** key held in the Android Keystore (hardware-backed
  where available). Only ciphertext is persisted. Message bodies and clipboard-style secrets are
  never logged.
- Neon URLs accept only `sslmode=require/verify-ca/verify-full`; the password is masked
  (`password=••••••`) for display.
- A client-side key cannot be made impossible to extract from a compromised device or a
  debugger attached to the app; treat the key as revocable and **rotate it** if it is ever
  exposed.
- Raw server error bodies are not surfaced to the user or logged.

## Testing

- **JVM unit tests** — crypto format/cipher, secret storage, Room entity mapping, repository
  send/retry, Neon repository (search/save/recall, failure mapping), the tool loop (scope
  propagation, approval + replay prevention), memory recall relevance/fencing, and credential
  import validation.
- **Instrumentation tests** — Android Keystore round-trip, non-exportability, and key
  invalidation on an API 34 emulator.

Tests use fakes and in-memory doubles — they never send real requests or consume credits.

## Limitations

- Conversation history survives config changes and app restarts (Room), but an **in-flight
  request does not survive process death**.
- Personal-memory recall uses token-overlap relevance (no embeddings), so paraphrased or
  cross-language recall is not yet supported.
- Gmail OAuth needs a **real Google client id + on-device redirect test** (CI cannot exercise it).
- Direct Android→PostgreSQL JDBC access; a backend API boundary is proposed in
  [`docs/PRODUCTION-HARDENING.md`](docs/PRODUCTION-HARDENING.md).
- Streaming and the model-driven loop need a **real-device + live-key** pass for UX polish.
