# NEXUS Agent

A standalone Android AI assistant built with Kotlin and Jetpack Compose. It is a
private-by-default chat app backed by your own [DeepSeek](https://platform.deepseek.com)
API key — no Termux, no third-party backend, no bundled credentials.

> Status: **in development**. The chat, streaming, persistence, encrypted key storage,
> and localization are implemented and CI-verified. Not yet published to any store.

## Features

- **Chat with DeepSeek** — `deepseek-v4-pro` and `deepseek-flash` models, streaming
  (token-by-token) responses.
- **Local history** — conversation persisted on-device with Room.
- **Encrypted API key** — your DeepSeek key is stored as AES-256-GCM ciphertext in the
  Android Keystore; the plaintext is never written to disk.
- **Arabic & English** — full string resources with RTL support; follows the system locale.
- **Light & dark theme** — follows the system setting.
- **Minimal permissions** — only `INTERNET`.

## Architecture

```
com.nexus.agent
├── ui/            Compose screens + ViewModels (ChatScreen, ChatViewModel) + theme
├── domain/model/  ChatMessage
├── data/
│   ├── network/   DeepSeekClient (OkHttp + kotlinx.serialization), SSE parsing
│   ├── local/     Room (MessageEntity, MessageDao, NexusDatabase)
│   └── repository/ ChatRepository, SecretStorageRepository (DataStore + CryptoEngine)
└── security/      Android Keystore AES-GCM (CryptoEngine, AesGcmCipher, …)
```

Data flows: `ChatScreen` → `ChatViewModel` → `ChatRepository` → (`DeepSeekClient` for
network, `MessageDao` for persistence). API-key reads go through `SecretStorageRepository`,
which decrypts on demand and returns explicit states (`Present` / `Missing` / `Corrupted` /
`KeyUnavailable`) instead of silently failing.

## Tech stack

| Component | Version |
|---|---|
| Kotlin | 1.9.24 |
| Android Gradle Plugin | 8.5.2 |
| Gradle wrapper | 8.7 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 24 |
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
3. Open the app → **Settings** → paste the key → **Save**.

The key is stored encrypted on-device. **Never commit a key** — there is no bundled or
default key, and no key is ever logged.

## Security model

- The API key is encrypted with AES-256-GCM using a **non-exportable** key held in the
  Android Keystore (hardware-backed where available). Only ciphertext is persisted in
  DataStore. Message bodies and clipboard-style secrets are never logged.
- A client-side key cannot be made impossible to extract from a compromised device or a
  debugger attached to the app; treat the key as revocable and **rotate it** if it is ever
  exposed.
- Raw server error bodies are not surfaced to the user or logged.

## Testing

- **JVM unit tests** — crypto format/cipher, secret storage (DataStore round-trip, corruption,
  invalidation), Room entity mapping, and repository send/retry (with fake client/DAO).
- **Instrumentation tests** — Android Keystore round-trip, non-exportability, and key
  invalidation on an API 34 emulator.

Tests use fakes and in-memory doubles — they never send real requests or consume credits.

## Limitations / roadmap

- Conversation history survives config changes and app restarts (Room), but an **in-flight
  request does not survive process death**.
- Streaming is implemented but needs a **real-device + live-key** check for UX polish
  (token-by-token rendering, the brief completion flicker).
- Planned: onboarding/first-launch flow, an explicit language switch (today it follows the
  system locale), theme override (system/light/dark), and a settings screen for model choice.
- Not planned for now: tool-calling/device-control, accounts integration, billing.
