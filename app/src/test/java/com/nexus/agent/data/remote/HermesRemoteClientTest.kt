package com.nexus.agent.data.remote

import com.nexus.agent.domain.hermes.HermesRunEvent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesRemoteClientTest {

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

    private fun client() = HermesRemoteClientImpl(
        endpointProvider = { baseUrl },
        apiKeyProvider = { "test-key" }
    )

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `capabilities sends bearer auth and decodes`() = runTest {
        server.enqueue(json("""{"features":{"run_submission":true,"run_status":true,"run_events_sse":true}}"""))
        val result = client().capabilities()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.supportsRuns == true)
        val recorded = server.takeRequest()
        assertEquals("/v1/capabilities", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun `health returns ok`() = runTest {
        server.enqueue(json("""{"status":"ok"}"""))
        val result = client().health()
        assertTrue(result.getOrNull()?.isOk == true)
        assertEquals("/health", server.takeRequest().path)
    }

    @Test
    fun `createRun posts the request body`() = runTest {
        server.enqueue(json("""{"run_id":"r1","status":"started"}""", code = 202))
        val result = client().createRun(HermesRunRequest(input = "hello", sessionId = "s1"))
        assertTrue(result.isSuccess)
        assertEquals("r1", result.getOrNull()?.runId)
        val recorded = server.takeRequest()
        assertEquals("/v1/runs", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"input\":\"hello\""))
        assertTrue(body.contains("\"session_id\":\"s1\""))
    }

    @Test
    fun `getRun decodes status`() = runTest {
        server.enqueue(json("""{"run_id":"r1","status":"completed","output":"done"}"""))
        val result = client().getRun("r1")
        assertTrue(result.isSuccess)
        assertEquals("completed", result.getOrNull()?.status)
        assertEquals("/v1/runs/r1", server.takeRequest().path)
    }

    @Test
    fun `stopRun posts to stop endpoint`() = runTest {
        server.enqueue(json("""{"status":"stopping"}"""))
        val result = client().stopRun("r1")
        assertTrue(result.isSuccess)
        assertEquals("stopping", result.getOrNull()?.status)
        assertEquals("/v1/runs/r1/stop", server.takeRequest().path)
    }

    @Test
    fun `approveRun posts approval body`() = runTest {
        server.enqueue(json("""{"status":"ok"}"""))
        val result = client().approveRun("r1", HermesApprovalRequest("p1", 3, HermesApprovalRequest.CHOICE_ONCE))
        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("/v1/runs/r1/approval", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"request_id\":\"p1\""))
        assertTrue(body.contains("\"execution_generation\":3"))
        assertTrue(body.contains("\"choice\":\"once\""))
    }

    @Test
    fun `streamRunEvents emits typed events to terminal`() = runTest {
        val body = "event: tool.started\ndata: {\"tool\":\"web_search\"}\n\n" +
            "event: message.delta\ndata: {\"text\":\"hi \"}\n\n" +
            "event: run.completed\ndata: {\"output\":\"hi there\"}\n\n"
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body)
        )
        val events = mutableListOf<HermesRunEvent>()
        val result = client().streamRunEvents("r1") { events.add(it) }

        assertTrue(result.isSuccess)
        assertEquals(3, events.size)
        assertTrue(events[0] is HermesRunEvent.ToolStarted)
        assertTrue(events[1] is HermesRunEvent.Delta)
        assertTrue(events[2] is HermesRunEvent.Completed)
        assertEquals("hi there", (events[2] as HermesRunEvent.Completed).output)
        assertEquals("/v1/runs/r1/events", server.takeRequest().path)
    }

    @Test
    fun `non-success response surfaces HermesHttpException code`() = runTest {
        server.enqueue(json("""{"error":"nope"}""", code = 401))
        val result = client().capabilities()
        assertTrue(result.isFailure)
        val e = result.exceptionOrNull()
        assertTrue(e is HermesHttpException)
        assertEquals(401, (e as HermesHttpException).code)
    }
}
