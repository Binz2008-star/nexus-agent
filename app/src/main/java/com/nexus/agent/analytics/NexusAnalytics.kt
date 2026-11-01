package com.nexus.agent.analytics

import android.app.Application
import com.nexus.agent.BuildConfig
import com.posthog.PersonProfiles
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

/**
 * Anonymous, explicitly opt-in operational analytics.
 *
 * Events have fixed names and NO properties. Never send prompts, model responses,
 * contact details, Gmail data, URLs, tool arguments/results or memory content.
 * Auto capture, screen recordings, deep links, crash payloads and person profiles
 * remain disabled even when the user opts in.
 */
object NexusAnalytics {
    enum class Event(val key: String) {
        APP_OPENED("nexus_app_opened"),
        CHAT_SUBMITTED("nexus_chat_submitted")
    }

    @Volatile
    var isConfigured: Boolean = false
        private set

    @Volatile
    private var hasConsent = false

    /** Called exactly once from Application.onCreate(). No events leave by default. */
    fun initialize(application: Application) {
        val token = BuildConfig.POSTHOG_PROJECT_TOKEN.trim()
        val host = BuildConfig.POSTHOG_HOST.trim()
        if (token.isEmpty() || !host.startsWith("https://")) return

        val config = PostHogAndroidConfig(apiKey = token, host = host).apply {
            optOut = true
            captureApplicationLifecycleEvents = false
            captureScreenViews = false
            captureDeepLinks = false
            sessionReplay = false
            errorTrackingConfig.autoCapture = false
            personProfiles = PersonProfiles.NEVER
            preloadFeatureFlags = false
            sendFeatureFlagEvent = false
            debug = false
        }
        PostHogAndroid.setup(application, config)
        isConfigured = true
    }

    /** User-controlled; disable stops all subsequent event capture immediately. */
    fun setConsent(enabled: Boolean) {
        hasConsent = enabled && isConfigured
        if (!isConfigured) return
        if (hasConsent) PostHog.optIn() else PostHog.optOut()
    }

    /** Deliberately accepts no properties or user-provided strings. */
    fun track(event: Event) {
        if (!isConfigured || !hasConsent) return
        PostHog.capture(event = event.key, properties = emptyMap())
    }
}
