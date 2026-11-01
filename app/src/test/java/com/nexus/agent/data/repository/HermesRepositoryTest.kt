package com.nexus.agent.data.repository

import com.nexus.agent.data.remote.HermesApprovalRequest
import com.nexus.agent.data.remote.HermesApprovalResult
import com.nexus.agent.data.remote.HermesCapabilities
import com.nexus.agent.data.remote.HermesChatRequest
import com.nexus.agent.data.remote.HermesFeatures
import com.nexus.agent.data.remote.HermesHealth
import com.nexus.agent.data.remote.HermesHttpException
import com.nexus.agent.data.remote.HermesRemoteClient
import com.nexus.agent.data.remote.HermesRunCreated
import com.nexus.agent.data.remote.HermesRunRequest
import com.nexus.agent.data.remote.HermesRunStatus
import com.nexus.agent.data.remote.HermesStopResult
import com.nexus.agent.domain.hermes.HermesRunEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRepositoryTest {

    private class FakeClient(
        private val capabilities: Result<HermesCapabilities> = Result.success(
            HermesCapabilities(features = HermesFeatures(runSubmission = true, runStatus = true, runEventsSse = true))
        ),
        private val health: Result<HermesHealth> = Result.success(HermesHealth(status = "ok"))
    ) : HermesRemoteClient {
        override suspend fun capabilities() = capabilities
        override suspend fun health() = health
        override suspend fun chat(request: HermesChatRequest): Result<String> = Result.success("")
        override suspend fun createRun(request: HermesRunRequest): Result<HermesRunCreated> =
            Result.success(HermesRunCreated("r1", "started"))
        override suspend fun getRun(runId: String): Result<HermesRunStatus> =
            Result.success(HermesRunStatus(runId = runId, status = "completed"))
        override suspend fun approveRun(runId: String, request: HermesApprovalRequest): Result<HermesApprovalResult> =
            Result.success(HermesApprovalResult())
        override suspend fun stopRun(runId: String): Result<HermesStopResult> =
            Result.success(HermesStopResult("stopping"))
        override suspend fun streamRunEvents(runId: String, onEvent: suspend (HermesRunEvent) -> Unit): Result<String> {
            onEvent(HermesRunEvent.Completed("done"))
            return Result.success("completed")
        }
    }

    @Test
    fun `verifyConnection becomes ready when runs supported and healthy`() = runTest {
        val repo = HermesRepository(FakeClient())
        val state = repo.verifyConnection()
        assertTrue(state is HermesConnectionState.Ready)
        assertTrue(repo.connectionState.value is HermesConnectionState.Ready)
    }

    @Test
    fun `verifyConnection errors when runs unsupported`() = runTest {
        val repo = HermesRepository(
            FakeClient(capabilities = Result.success(HermesCapabilities(features = HermesFeatures())))
        )
        val state = repo.verifyConnection()
        assertTrue(state is HermesConnectionState.Error)
        assertEquals(HermesError.ERR_RUNS_UNSUPPORTED, (state as HermesConnectionState.Error).code)
    }

    @Test
    fun `verifyConnection errors when unhealthy`() = runTest {
        val repo = HermesRepository(FakeClient(health = Result.success(HermesHealth(status = "degraded"))))
        val state = repo.verifyConnection()
        assertTrue(state is HermesConnectionState.Error)
        assertEquals(HermesError.ERR_UNHEALTHY, (state as HermesConnectionState.Error).code)
    }

    @Test
    fun `verifyConnection maps auth errors`() = runTest {
        val repo = HermesRepository(
            FakeClient(capabilities = Result.failure(HermesHttpException(401, "unauthorized")))
        )
        val state = repo.verifyConnection()
        assertTrue(state is HermesConnectionState.Error)
        assertEquals("auth", (state as HermesConnectionState.Error).code)
    }

    @Test
    fun `error code maps https required`() {
        assertEquals("https_required", HermesError.codeOf(IllegalStateException("hermes_endpoint_https_required")))
    }
}
