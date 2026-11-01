package com.nexus.agent.data.repository

import com.nexus.agent.data.remote.HermesApprovalRequest
import com.nexus.agent.data.remote.HermesApprovalResult
import com.nexus.agent.data.remote.HermesHttpException
import com.nexus.agent.data.remote.HermesRemoteClient
import com.nexus.agent.data.remote.HermesRunCreated
import com.nexus.agent.data.remote.HermesRunRequest
import com.nexus.agent.data.remote.HermesRunStatus
import com.nexus.agent.data.remote.HermesStopResult
import com.nexus.agent.domain.hermes.HermesRunEvent
import com.nexus.agent.domain.hermes.HermesSafety
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connection/verification state of the Hermes backend, observed by the UI. */
sealed interface HermesConnectionState {
    /** No endpoint/key configured yet. */
    data object Unconfigured : HermesConnectionState

    /** A verification round-trip (capabilities + health) is in flight. */
    data object Verifying : HermesConnectionState

    /** The endpoint answered and advertises the Runs API. */
    data object Ready : HermesConnectionState

    /** Verification failed (auth / unreachable / unsupported / invalid endpoint). */
    data class Error(val code: String) : HermesConnectionState
}

/**
 * Thin facade over [HermesRemoteClient] that owns the backend's connection state. The ViewModel
 * drives the run lifecycle through the delegated methods; audit logging lives at the ViewModel so
 * it can record tool names and lifecycle events without ever recording secrets.
 */
class HermesRepository(
    private val client: HermesRemoteClient
) {
    private val _connectionState = MutableStateFlow<HermesConnectionState>(HermesConnectionState.Unconfigured)
    val connectionState: StateFlow<HermesConnectionState> = _connectionState.asStateFlow()

    /** Verifies the backend advertises the Runs API and is healthy; updates [connectionState]. */
    suspend fun verifyConnection(): HermesConnectionState {
        _connectionState.value = HermesConnectionState.Verifying
        val outcome: Result<Unit> = try {
            val capabilities = client.capabilities().getOrThrow()
            if (!capabilities.supportsRuns) {
                throw IllegalStateException(HermesError.ERR_RUNS_UNSUPPORTED)
            }
            val health = client.health().getOrThrow()
            if (!health.isOk) throw IllegalStateException(HermesError.ERR_UNHEALTHY)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
        val state = outcome.fold(
            onSuccess = { HermesConnectionState.Ready },
            onFailure = { HermesConnectionState.Error(HermesError.codeOf(it)) }
        )
        _connectionState.value = state
        return state
    }

    suspend fun createRun(request: HermesRunRequest): Result<HermesRunCreated> = client.createRun(request)

    suspend fun getRun(runId: String): Result<HermesRunStatus> = client.getRun(runId)

    suspend fun approveRun(runId: String, request: HermesApprovalRequest): Result<HermesApprovalResult> =
        client.approveRun(runId, request)

    suspend fun stopRun(runId: String): Result<HermesStopResult> = client.stopRun(runId)

    suspend fun streamRunEvents(
        runId: String,
        onEvent: suspend (HermesRunEvent) -> Unit
    ): Result<String> = client.streamRunEvents(runId, onEvent)
}

/** Maps a Hermes failure to a stable, user-facing error code (mirrors ChatRepository.safeErrorCode). */
object HermesError {
    const val ERR_RUNS_UNSUPPORTED = "hermes_runs_unsupported"
    const val ERR_UNHEALTHY = "hermes_unhealthy"

    fun codeOf(t: Throwable): String = when {
        t is HermesHttpException && t.code == 401 -> "auth"
        t is HermesHttpException && t.code == 403 -> "auth"
        t is HermesHttpException && t.code == 429 -> "rate"
        t is HermesHttpException -> "api_${t.code}"
        t.message?.contains(ERR_RUNS_UNSUPPORTED) == true -> ERR_RUNS_UNSUPPORTED
        t.message?.contains(ERR_UNHEALTHY) == true -> ERR_UNHEALTHY
        t.message?.contains(HermesSafety.ERR_HTTPS_REQUIRED) == true -> "https_required"
        t.message?.contains(HermesSafety.ERR_NO_KEY) == true -> "no_key"
        t.message?.contains(HermesSafety.ERR_MALFORMED) == true -> "endpoint"
        t.message?.contains("resolve host", ignoreCase = true) == true ||
            t.message?.contains("Failed to connect") == true -> "network"
        else -> "generic:${t.javaClass.simpleName}"
    }
}
