package com.nexus.agent.data.remote

import com.nexus.agent.domain.tool.KnowledgeSearchResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NeonHttpClientTest {

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

    private fun client(
        baseUrlProvider: suspend () -> String? = { baseUrl },
        apiKeyProvider: suspend () -> String? = { "test-key" }
    ) = NeonHttpClient(baseUrlProvider, apiKeyProvider)

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `search sends bearer auth and decodes results`() = runTest {
        server.enqueue(
            json(
                """{"results":[{"project":"proj","file":"f.txt","chunk_type":"txt","content":"hello","score":0.9}]}"""
            )
        )
        val result = client().search("hello", 8)
        assertTrue(result is KnowledgeSearchResult.Success)
        val success = result as KnowledgeSearchResult.Success
        assertEquals("hello", success.query)
        assertEquals(1, success.results.size)
        assertEquals("proj", success.results[0].project)
        assertEquals("f.txt", success.results[0].file)
        assertEquals("txt", success.results[0].chunk)
        assertEquals(0.9, success.results[0].score, 0.0001)

        val recorded = server.takeRequest()
        assertEquals("/v1/brain/search", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"query\":\"hello\""))
        assertTrue(body.contains("\"top_k\":8"))
    }

    @Test
    fun `search maps 401 to auth failure`() = runTest {
        server.enqueue(json("""{"detail":"unauthorized"}""", code = 401))
        val result = client().search("x", 8)
        assertTrue(result is KnowledgeSearchResult.Failure)
        assertEquals("auth", (result as KnowledgeSearchResult.Failure).errorCode)
    }

    @Test
    fun `search surfaces backend detail error code`() = runTest {
        server.enqueue(json("""{"detail":"network"}""", code = 502))
        val result = client().search("x", 8)
        assertTrue(result is KnowledgeSearchResult.Failure)
        assertEquals("network", (result as KnowledgeSearchResult.Failure).errorCode)
    }

    @Test
    fun `search surfaces permission detail error code`() = runTest {
        server.enqueue(json("""{"detail":"permission"}""", code = 502))
        val result = client().search("x", 8)
        assertTrue(result is KnowledgeSearchResult.Failure)
        assertEquals("permission", (result as KnowledgeSearchResult.Failure).errorCode)
    }

    @Test
    fun `search handles non-json error body`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html>bad gateway</html>")
        )
        val result = client().search("x", 8)
        assertTrue(result is KnowledgeSearchResult.Failure)
        assertEquals("http_502", (result as KnowledgeSearchResult.Failure).errorCode)
    }

    @Test
    fun `search rejects cleartext non-loopback backend url`() = runTest {
        // TLS is enforced at request time: an http:// non-loopback URL must never send the bearer key.
        val result = client(baseUrlProvider = { "http://example.com" }).search("x", 8)
        assertTrue(result is KnowledgeSearchResult.Failure)
        assertEquals(NeonHttpClient.ERROR_NO_CONNECTION, (result as KnowledgeSearchResult.Failure).errorCode)
    }

    @Test
    fun `search fails with no_connection when base url is absent`() = runTest {
        val result = client(baseUrlProvider = { null }).search("x", 8)
        assertTrue(result is KnowledgeSearchResult.Failure)
        assertEquals(NeonHttpClient.ERROR_NO_CONNECTION, (result as KnowledgeSearchResult.Failure).errorCode)
    }

    @Test
    fun `saveMemory posts body with tags array`() = runTest {
        server.enqueue(json("""{"ok":true}"""))
        val result = client().saveMemory("fact", "remember", listOf("a", "b c"))
        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("/v1/memory/save", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"type\":\"fact\""))
        assertTrue(body.contains("\"content\":\"remember\""))
        assertTrue(body.contains("\"tags\":[\"a\",\"b c\"]"))
    }

    @Test
    fun `loadMemory encodes limit and decodes items`() = runTest {
        server.enqueue(json("""{"items":[{"type":"fact","content":"c1"},{"type":"pref","content":"c2"}]}"""))
        val result = client().loadMemory(limit = 50)
        assertTrue(result.isSuccess)
        val items = result.getOrNull()!!
        assertEquals(2, items.size)
        assertEquals("fact", items[0].type)
        assertEquals("c2", items[1].content)
        val recorded = server.takeRequest()
        assertEquals("/v1/memory/recent?limit=50", recorded.path)
    }

    @Test
    fun `mirror posts session_id role content`() = runTest {
        server.enqueue(json("""{"ok":true}"""))
        val result = client().mirror("s1", "user", "hello")
        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("/v1/transcript/mirror", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"session_id\":\"s1\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"content\":\"hello\""))
    }
}
