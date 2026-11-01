package com.nexus.agent

import android.app.Application
import com.nexus.agent.analytics.NexusAnalytics

/** Initialize analytics once. Consent is restored from DataStore in ChatScreen. */
class NexusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NexusAnalytics.initialize(this)
    }
}
