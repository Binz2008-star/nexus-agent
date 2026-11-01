package com.nexus.agent.data.remote

import com.nexus.agent.domain.safety.UrlSafetyValidator
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class WebFetchClientTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // A validator that accepts the localhost-hosted MockWebServer (the SSRF logic itself is
    // covered by UrlSafetyValidatorTest; here we exercise the HTTP/redirect/extract + DNS-pin path).
    private fun permissiveValidator() = UrlSafetyValidator(
        resolver = { listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) },
        isHostBlocked = { false },
        isPublic = { true }
    )

    @Test
    fun `fetches and extracts title and text`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><head><title>Hello</title></head><body><p>Hello <b>world</b>.</p></body></html>")
        )
        val client = WebFetchClient(validator = permissiveValidator())
        val result = client.fetch("$baseUrl/page")
        assertTrue(result.isSuccess)
        val page = result.getOrNull()!!
        assertEquals("Hello", page.title)
        assertTrue(page.text.contains("Hello world"))
        assertEquals("$baseUrl/page", page.url)
    }

    @Test
    fun `follows redirect and returns final page`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("<html><head><title>Final</title></head><body>done</body></html>")
        )
        val client = WebFetchClient(validator = permissiveValidator())
        val result = client.fetch("$baseUrl/start")
        assertTrue(result.isSuccess)
        assertEquals("Final", result.getOrNull()?.title)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `rejects blocked url without touching the network`() = runTest {
        // Default validator blocks "localhost" (hostname blocklist), so nothing is fetched.
        val client = WebFetchClient()
        val result = client.fetch("http://localhost:1234/secret")
        assertTrue(result.isFailure)
        assertEquals(UrlSafetyValidator.ERR_BLOCKED, result.exceptionOrNull()?.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `returns failure on http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val client = WebFetchClient(validator = permissiveValidator())
        val result = client.fetch("$baseUrl/missing")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("404") == true)
    }
}
