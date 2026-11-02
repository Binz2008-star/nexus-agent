package com.nexus.agent.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** A 307/308 redirect must never replay an explicitly approved Gmail POST. */
class GmailSendRedirectRegressionTest {
    @Test fun permanentAndTemporaryRedirectsDoNotReplayApprovedPost() {
        for (status in listOf(307, 308)) {
            val server = MockWebServer()
            try {
                server.start()
                server.enqueue(
                    MockResponse()
                        .setResponseCode(status)
                        .addHeader("Location", "/replayed")
                )
                val hardened = GmailSendHttpPolicy.harden(OkHttpClient())
                val request = Request.Builder()
                    .url(server.url("/send"))
                    .post("fixture-body".toRequestBody())
                    .build()
                hardened.newCall(request).execute().use { response ->
                    assertEquals(status, response.code)
                }
                assertEquals(1, server.requestCount)
                assertEquals("/send", server.takeRequest().path)
            } finally {
                server.shutdown()
            }
        }
    }
}
