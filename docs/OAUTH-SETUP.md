# NEXUS Agent — Gmail OAuth setup (Google Cloud Console)

The app uses the standard OAuth 2.0 authorization-code flow with **PKCE** via AppAuth, opening the
system browser (Custom Tabs) and receiving the redirect through the private-use scheme
`com.nexus.agent:/oauth2redirect`. This is the Google-supported approach for installed/native apps
(RFC 8252, which Google co-authored); Google Identity Services (GIS) is for *Sign-In*, not for
scoped Gmail API access, so the AppAuth flow is correct here. The app is a **public client** — no
client secret is used or embedded.

## What you need to create (one-time, in your Google Cloud account)

You must perform these steps yourself — the client ID comes from *your* Google Cloud project, and
NEXUS cannot create it for you.

1. Go to **Google Cloud Console → APIs & Services → OAuth consent screen**:
   - User type: **External** (or Internal if your org allows).
   - App name: **NEXUS Agent**; User support email: your email.
   - Add scope `https://www.googleapis.com/auth/gmail.readonly` (non-sensitive).
   - Add yourself as a **test user** (required while the app is in "Testing" publishing status).
2. **APIs & Services → Library** → enable the **Gmail API**.
3. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Desktop app**.
   - Name: `NEXUS Agent (Android)`.
   - Under **Authorized redirect URIs**, add exactly: `com.nexus.agent:/oauth2redirect`
4. Copy the generated **Client ID** (the `<digits>-<hash>.apps.googleusercontent.com` string).

> **Alternative ("Android" type):** if you instead choose the **Android** application type, provide
> package name `com.nexus.agent` and SHA-1 `15:88:D3:C3:46:01:62:71:66:76:5C:05:64:20:79:04:BD:CB:40:34`.
> That type is meant for the Google Sign-In SDK and auto-assigns a Google scheme; the current build
> uses the AppAuth custom scheme above, so the **Desktop app** type is the one that matches.

## Wiring the client ID into the build (no secret)

- Set the GitHub Actions secret `GOOGLE_OAUTH_CLIENT_ID` to the client ID value.
- CI injects it at build time via `buildConfigField` (`BuildConfig.GOOGLE_OAUTH_CLIENT_ID`).
- The client ID is a **public identifier** (safe to embed); no client secret or refresh token is
  ever embedded in the APK.

## Signing fingerprint (stable as of the signing stabilization)

- Certificate subject: `CN=Nexus Test Signing, O=Nexus, C=US`
- SHA-1: `15:88:D3:C3:46:01:62:71:66:76:5C:05:64:20:79:04:BD:CB:40:34`
- SHA-256: `7D:7A:B2:AE:4C:8A:37:DC:CF:75:42:11:FA:E1:0F:2F:4C:22:CF:06:11:E9:93:81:D6:70:30:8D:48:A9:DE:41`
