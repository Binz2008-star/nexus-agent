package com.nexus.agent.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailSendHttpPolicyTest {
    @Test fun defaultClientCannotSilentlyRetryOrRedirectAnApprovedPost() {
        val input = OkHttpClient()
        assertTrue(input.retryOnConnectionFailure)
        assertTrue(input.followRedirects)

        val safe = GmailSendHttpPolicy.harden(input)
        assertFalse(safe.retryOnConnectionFailure)
        assertFalse(safe.followRedirects)
        assertFalse(safe.followSslRedirects)
        assertEquals(30_000, safe.callTimeoutMillis)
    }

    @Test fun explicitlyUnsafeInjectedClientIsHardenedWithoutMutatingCaller() {
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val hardened = GmailSendHttpPolicy.harden(client)
        assertFalse(hardened.retryOnConnectionFailure)
        assertFalse(hardened.followRedirects)
        assertFalse(hardened.followSslRedirects)
        assertTrue(client.retryOnConnectionFailure)
        assertTrue(client.followRedirects)
    }
}
