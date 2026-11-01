# NEXUS — Google Workspace OAuth and service integrations

**Status: code integrated, but Gmail/Drive/Calendar/Contacts/Tasks not yet
verified on a physical Android device.** Do not publish or claim production readiness based
on CI alone. This replaces the old AppAuth custom-redirect instructions.

## Project and client configuration

Use the existing Google Cloud project `gmail-ai-bot-452308` and a Google OAuth **Android**
client registered for `com.nexus.agent` with the stable TEST APK signing SHA-1:

`15:88:D3:C3:46:01:62:71:66:76:5C:05:64:20:79:04:BD:CB:40:34`

The public Android OAuth client ID is now included as a build **fallback** so local
and CI builds cannot silently ship an empty Client ID. `GOOGLE_OAUTH_CLIENT_ID` may
override it at build time for another registered Android OAuth client. A Client ID is
**not a secret**: do not commit OAuth credential JSON, refresh tokens, private signing
keys, Neon URLs, or Gmail/Drive data. The build validates the Client ID format; this
does **not** establish that the configured package and test APK signing SHA-1 match
Google Cloud.

When upgrading a phone with a previously installed APK, use the **latest build artifact**
from a successful workflow run for the exact intended commit; an old installed APK will
not retroactively gain the client ID after updating GitHub secrets. If the update
is rejected for signature mismatch, do **not** uninstall/clear app data without an
encrypted backup and verified recovery procedure.

Do not use Desktop OAuth clients, localhost redirects, or
`com.nexus.agent:/oauth2redirect` in the new Android flow.

## Enable only the relevant APIs

In **Google Cloud → APIs & Services → Library**, enable each before testing:

- Gmail API
- Google Drive API (also supports Drive-file discovery/creation of Docs, Sheets, Slides)
- Google Calendar API
- People API (Contacts)
- Google Tasks API

In **Google Auth Platform → Data Access**, add only the OAuth scopes for the services that
will be tested, and register your account in **Audience → Test users** while in Testing.
Restricted/sensitive Gmail and Drive scopes may require verification/security assessment
for public distribution. Do not assume all Google services share an unrestricted consent.

## What is wired in this branch

| Capability | Required OAuth scope | Risk | Implemented action |
| --- | --- | --- | --- |
| Gmail search/read metadata | `gmail.readonly` | READ | Search messages |
| Gmail send plain text | `gmail.send` | COMMIT | One message per approved call |
| Drive search | `drive.readonly` | READ | Search file names and metadata |
| Drive create Doc / Sheet / Slide | `drive.file` | COMMIT | Create empty app-managed file only |
| Calendar | `calendar.events.readonly` | READ | List next 10 primary-calendar events |
| Contacts | `contacts.readonly` | READ | Read up to 20 names/email addresses |
| Tasks | `tasks.readonly` | READ | Read task-list names |

**Important:** Drive `drive.file` does not grant write access to every existing file.
Editing an existing document needs a selected/app-authorized file, a supported Docs/Sheets
or Slides API operation and a separate approved tool (not implemented in this PR).
The `drive.readonly` scope is broad and may require verification even when only file
metadata is being returned. The restricted Gmail scopes are handled separately.

The same Android OAuth client ID supports multiple services, but the **user explicitly
authorizes each service/scope** from Settings. Only the requested scopes are granted and
checked per tool. OAuth access tokens are not persisted by NEXUS; after app restart the
user may need to authorize again. Consent cannot be triggered by an autonomous tool
call. Gmail sending and Drive creation are protected by NEXUS one-shot approval receipts,
not by Google OAuth consent alone. Email body, recipient and document name appear in the
approval request; review before approving. No automatic resend after network timeout.

## Future modular integrations (not yet operational)

- Docs/Sheets/Slides **content editing** using approved Drive-selected file IDs
- Calendar **create/update**; Tasks **create/update**
- People API **contact creation** (optional high-risk)
- Google Forms, Keep, Chat, Meet, Photos, YouTube and additional APIs where supported
- App-specific Google Cloud APIs, only if required for business workflows

These require new reviewed tools, narrower scopes, per-operation approval, API activation,
possibly separate verification/billing, and device testing. "All Google services" is an
extensibility goal, not a blanket permission or a promise that every Google API offers
individual-account access.

## Acceptance tests (required before main merge)

1. Google Cloud Android client package and signing SHA-1 match APK; OAuth client injected.
2. Connect Gmail read: authorize, account identity verified, search a known email.
3. Grant Gmail send *separately*; review approval card with exact recipient/subject/body,
   accept and verify the returned Gmail sent message ID. Deny and verify no message sent.
4. Connect Drive read, search known files; grant Drive file separately, create a blank
   document after explicit approval and confirm it appears in Drive.
5. Connect Calendar/Contacts/Tasks individually and confirm read-only queries.
6. Test denied scopes, wrong Google account, revoked grants, expired token,
   offline/timeout, Google API disabled and app restart.
7. Verify Gmail, Drive and Contacts content are not logged, persisted or mirrored to Neon
   without separate explicit user consent. Confirm tool output remains untrusted data.
8. Test physical Android app update without Room loss; independent scan of resulting APK.
9. Review scope and COMMIT receipts for third-party/automatic tool selection.

**Launch gate:** No live device acceptance, no public publication, no main merge from
this feature branch without reviewed CI and owner approval.

Official references:
- https://developer.android.com/identity/authorization
- https://developers.google.com/identity/protocols/oauth2/scopes
- https://developers.google.com/workspace/gmail/api/auth/scopes
