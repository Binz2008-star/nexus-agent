# NEXUS Agent — Gmail OAuth setup and release gate

**Status: NOT VERIFIED for real-device Gmail authorization.**
Do not follow the older “Desktop app client + `com.nexus.agent:/oauth2redirect`” instructions.
A passing unit test and an embedded public client ID do not prove that Google accepts the
redirect or that a Gmail token can be obtained on an Android device.

## Current implementation (development only)

NEXUS currently implements an AppAuth PKCE browser flow, attempts a custom-scheme redirect
(`com.nexus.agent:/oauth2redirect`), and requests
`https://www.googleapis.com/auth/gmail.readonly`. The code may compile while the Google
authorization server rejects this flow. The custom redirect is **not** evidence of a
Google-supported production Android integration.

- Google's current Desktop Apps OAuth documentation states that custom URI schemes are
  **no longer supported on Android and Chrome apps**; loopback redirects are for desktop
  use and deprecated on mobile client types.
- Android custom-scheme authorization is disabled by default in Google Cloud and is
  vulnerable to app impersonation. It may be available by explicit advanced configuration,
  but that does **not** establish that our present AppAuth flow is correctly configured.
- `gmail.readonly` is a **restricted** Gmail scope, not a non-sensitive scope.
  OAuth app verification and additional requirements may apply, particularly when
  distributing to users or transmitting restricted data to a backend.

Source documentation (verify at time of implementation):
- https://developers.google.com/identity/protocols/oauth2/native-app
- https://support.google.com/googleapi/answer/6158849
- https://developers.google.com/workspace/gmail/api/auth/scopes
- https://developers.googleblog.com/improving-user-safety-in-oauth-flows-through-new-oauth-custom-uri-scheme-restrictions/

## Required remediation before live Gmail acceptance

1. Select an officially supported **Android** Google authorization method for the Gmail
   API and requested scope. Evaluate Google Identity Services AuthorizationClient and
   alternatives against the current Google developer documentation.
2. Register the correct **Android OAuth client** with package `com.nexus.agent` and the
   actual test signing certificate SHA-1; verify the correct redirect/client pairing
   in a real browser-to-app authorization exchange.
3. Enable Gmail API, configure the correct OAuth consent screen and restricted scope,
   and add authorized test users while in Testing mode. Do not change account settings
   without the owner taking the relevant steps.
4. Keep secrets/refresh tokens private and encrypted; a client ID is public but must not
   be confused with a client secret. No server credentials in the APK.
5. Test: connect, consent, browser redirect, token exchange, list/read messages, token
   refresh, disconnect, unauthorized search denial, and reinstall/update behavior on
   an actual Android device. Record evidence without personal email content.

## Signing and release

CI injects a dedicated, stable **test signing** keystore from GitHub encrypted secrets;
this is not a production signing identity. Check the exact certificate fingerprint of
each build. Existing installations signed with a different certificate may reject an
upgrade. Back up valuable on-device data before considering uninstall/reinstall.

**Do not claim that Gmail works on a physical Android device until these checks pass.**
The rest of the PR (memory, tool scopes, context injection) may be integrated into
development `main` independently of this release gate.
