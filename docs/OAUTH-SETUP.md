# NEXUS Gmail OAuth — Android client authorization

**Status:** Code migrated to Google Identity Services in draft PR. GitHub CI and real-device OAuth acceptance must pass before Gmail is considered operational. This is **not** a production readiness signoff.

## Google Cloud configuration

Project: `gmail-ai-bot-452308` (Gmail-AI-Bot).

1. Configure Google Auth Platform consent screen under the selected project and add the intended Google account as a **test user** if External/Testing.
2. Enable **Gmail API** in that same project.
3. Use an **Android** OAuth 2.0 Client with package name `com.nexus.agent` and the exact certificate SHA-1 of the installed APK. The current stable *test* APK signer SHA-1 is `15:88:D3:C3:46:01:62:71:66:76:5C:05:64:20:79:04:BD:CB:40:34`. Production signing needs a separately registered fingerprint.
4. The public Android OAuth Client ID can be supplied to **GitHub Actions repository secret** `GOOGLE_OAUTH_CLIENT_ID`. This is injected into `BuildConfig` only to confirm a configured build; it is **not a client secret** and is not sent as a custom-redirect client credential.
5. Do **not** commit a `client_secret_*.json` file or any client secret, refresh token, Google account password, APK signing key, or private credentials.

## Android authorization implementation

- Uses `com.google.android.gms:play-services-auth:22.0.0` and `Identity.getAuthorizationClient(context)`.
- The user explicitly taps **Settings → Gmail → Connect**; requested scope is **only** `https://www.googleapis.com/auth/gmail.readonly`.
- Google Play services either returns an already-authorized result or a PendingIntent. NEXUS launches the PendingIntent via Android's Activity Result API, then checks the granted scopes **and** reads `users/me/profile` to confirm Gmail API access and the current account.
- The UI shows a generic failure message if authorization is rejected/cancelled, Gmail API access cannot be verified, or Play services is unavailable.
- Background Gmail tool calls may silently renew an already-connected token, but **never start consent UI**, expand scopes or run send/delete/modify actions.
- **No offline/refresh-token flow and no persistent Google access token in this phase.** After app process restart, the user may need to tap Connect again. This avoids introducing a backend client secret or token storage before the backend architecture is designed.
- Disconnect clears the local authorization state and legacy AppAuth secret keys; when a verified Gmail email is known it also attempts to revoke access through Google Play services. Revocation errors are reported; the app remains disconnected locally.
- The obsolete custom-scheme AppAuth browser intent filters and code exchange are removed.

This design intentionally differs from a server-side/offline integration. If future automation requires Gmail access while the app is closed, use an authenticated backend with a **separate Web OAuth Client**, server-owned token exchange, encrypted refresh-token storage, and appropriate OAuth review. Never embed a Web client secret into Android.

## Validation / release gates

A successful CI build does **not** prove real Gmail authorization. On a physical Android device with Google Play services:

1. Verify that the APK certificate SHA-1 matches the Android OAuth client in Google Cloud.
2. Verify Gmail API enabled, External test user and consent-screen/scopes.
3. Install without losing local Room data; tap Connect, select the expected account and consent.
4. Verify the **actual Gmail profile** and read-only message search result; confirm permission denial and cancellation do not connect.
5. Test restart/reconnect, revoked scope, multiple accounts, lost network, expired access token, and Disconnect/revoke.
6. Confirm no Gmail access token, refresh token, full mailbox contents or OAuth private material appears in logs, APK or Neon transcript mirror.
7. Run Google OAuth restricted-scope verification/security assessment as applicable **before public distribution**.

## References

- https://developer.android.com/identity/authorization
- https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration
- https://developers.google.com/android/reference/com/google/android/gms/auth/api/identity/AuthorizationClient
- https://developers.google.com/workspace/gmail/api/auth/scopes
- https://developers.google.com/android/guides/releases

## Known constraints

- Gmail `gmail.readonly` is a Google **restricted** scope. Public release may require Google verification and additional independent security assessment depending on where data is stored/transmitted.
- Current NEXUS Android → Neon direct JDBC remains a separate production blocker.
- A Google Cloud OAuth Client ID alone does not prove correct consent screen, Gmail API enablement or live authorization.
