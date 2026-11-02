package com.nexus.agent.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeonHttpPolicyTest {
    @Test fun defaultClientCannotAutomaticallyReplayDatabaseMutations() {
        val defaultClient = OkHttpClient()
        assertTrue(defaultClient.retryOnConnectionFailure)
        assertTrue(defaultClient.followRedirects)
        val safe = NeonHttpPolicy.harden(defaultClient)
        assertFalse(safe.retryOnConnectionFailure)
        assertFalse(safe.followRedirects)
        assertFalse(safe.followSslRedirects)
        assertEquals(60_000, safe.callTimeoutMillis)
    }

    @Test fun injectedClientIsHardenedWithoutMutatingCaller() {
        val injected = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val safe = NeonHttpPolicy.harden(injected)
        assertFalse(safe.retryOnConnectionFailure)
        assertFalse(safe.followRedirects)
        assertFalse(safe.followSslRedirects)
        assertTrue(injected.retryOnConnectionFailure)
        assertTrue(injected.followRedirects)
    }
}
