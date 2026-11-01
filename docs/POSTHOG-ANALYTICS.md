# NEXUS Android — PostHog analytics

This integration is **off by default**. It is intentionally separate from the app's
conversation-history mirroring and from its emotional-processing features.

## Setup for a CI-built APK

1. In GitHub repository Settings > Secrets and variables > Actions, create the
   repository secret `POSTHOG_PROJECT_TOKEN`. Copy the **project token** from
   PostHog project settings, not a personal API key. Never paste it into
   source files, issues, PR comments, or chat.
2. GitHub Actions passes `POSTHOG_PROJECT_TOKEN` to the Android build and sets
   `POSTHOG_HOST=https://us.i.posthog.com` for the configured US-region project.
   For local builds, export both environment variables before running Gradle.
3. Install the new APK and open **Settings**. The switch
   **Share anonymous usage counts** is off until the user enables it.
   If the token or HTTPS host is missing, the switch is disabled and no events
   are sent. An existing local consent preference is preserved across app restarts.
4. Confirm `nexus_app_opened` and `nexus_chat_submitted` in PostHog live events.
   The first counts an app-session start after consent is restored, and also
   records when consent is newly enabled. The second counts user submissions;
   it does not indicate that a request succeeded. These are not unique users.

## Data minimization

Only the two fixed event names are explicitly captured; their property maps
are empty. The integration does NOT include chat text, prompts, completions,
email addresses, Gmail results, contact data, memory, model choices, tool
arguments, database content, or identifiers from NEXUS.

Automatic lifecycle events, screen tracking, deep-link tracking, mobile session
replay, automatic error capture, automatic feature-flag events, and person
profiles are disabled. PostHog may still attach SDK-defined transport metadata
(e.g., timestamps or anonymous instance identifiers) to events: **do not describe
this as zero-metadata tracking**.

Disabling the switch immediately opts out of subsequent capture. Already sent
events must be handled through PostHog's data-management policies; turning off
the switch does not erase historical ingested events.

## Build and validation

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```

On-device acceptance criteria:
- Fresh install with switch off: no NEXUS app events transmitted.
- Build without `POSTHOG_PROJECT_TOKEN`: no events and switch disabled.
- Opt in: exactly the fixed event names appear without app-provided properties.
- Send a message: only `nexus_chat_submitted`, never message body.
- Opt out: subsequent chat submissions generate no new NEXUS events.
- Restart, verify previous consent preference and event behavior.
- Repeat while Gmail, memory search, Neon, and DeepSeek are in use; no
  sensitive payload should appear in PostHog.
- Test Arabic and English layouts.

Do not claim release readiness before GitHub CI and live-device verification.

Related: https://posthog.com/docs/libraries/android
