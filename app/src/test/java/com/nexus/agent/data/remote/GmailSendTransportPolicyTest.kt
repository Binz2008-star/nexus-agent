package com.nexus.agent.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GmailSendTransportPolicyTest {
    private val server = MockWebServer()

    @Before fun setup() { server.start() }

    @After fun cleanup() {
        server.shutdown()
    }

    @Test fun approvedSendClientNeverRetriesConnectionsOrFollowsRedirects() {
        val callerClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val send = GmailSendApiClient(tokenProvider = { "fixture-token" }, client = callerClient)
        assertFalse(send.commitHttpClient.retryOnConnectionFailure)
        assertFalse(send.commitHttpClient.followRedirects)
        assertFalse(send.commitHttpClient.followSslRedirects)
    }

    @Test fun redirectionDoesNotReplayApprovedPost() {
        server.enqueue(MockResponse().setResponseCode(307).addHeader(
            "Location", "/replay"
        ))
        val send = GmailSendApiClient(tokenProvider = { "fixture-token" })
        val request = Request.Builder()
            .url(server.url("/send"))
            .post("test".toRequestBody())
            .build()
        send.executeOnce(request).use { response ->
            assertEquals(307, response.code)
        }
        assertEquals(1, server.requestCount)
        assertEquals("/send", server.takeRequest().path)
    }
}
