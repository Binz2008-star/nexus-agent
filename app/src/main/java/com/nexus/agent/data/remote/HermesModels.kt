package com.nexus.agent.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Hermes Agent API Server DTOs (kotlinx-serialization).
 *
 * Mirrors the official OpenAI-compatible API server surface documented at
 * hermes-agent.nousresearch.com/docs/user-guide/features/api-server — specifically the Runs API,
 * `/v1/capabilities`, `/health`, and the `/v1/chat/completions` completion shape.
 *
 * All fields use explicit defaults so a partial/evolving server payload degrades gracefully
 * instead of failing deserialization (matching the app's `ignoreUnknownKeys` Json config).
 */

// ---- Request: POST /v1/runs ----

@Serializable
data class HermesRunRequest(
    val input: String,
    @SerialName("session_id") val sessionId: String? = null,
    val instructions: String? = null,
    @SerialName("conversation_history") val conversationHistory: List<HermesHistoryMessage>? = null,
    @SerialName("previous_response_id") val previousResponseId: String? = null
)

/** A single message in the optional `conversation_history` array. */
@Serializable
data class HermesHistoryMessage(
    val role: String,
    val content: String
)

// ---- Response: POST /v1/runs (HTTP 202) ----

@Serializable
data class HermesRunCreated(
    @SerialName("run_id") val runId: String = "",
    val status: String = "started"
)

// ---- Response: GET /v1/runs/{id} ----

@Serializable
data class HermesRunStatus(
    @SerialName("object") val obj: String? = null,
    @SerialName("run_id") val runId: String = "",
    val status: String = "",
    @SerialName("session_id") val sessionId: String? = null,
    val model: String? = null,
    val output: String? = null,
    val usage: HermesUsage? = null,
    val runtime: HermesRuntime? = null,
    @SerialName("pending_controls") val pendingControls: List<HermesPendingControl> = emptyList(),
    val error: HermesErrorInfo? = null,
    @SerialName("shutdown_requested_at") val shutdownRequestedAt: Long? = null
) {
    /** True when the run reached a terminal state (no further progress will occur). */
    val isTerminal: Boolean
        get() = status in TERMINAL_STATUSES

    companion object {
        val TERMINAL_STATUSES = setOf("completed", "failed", "cancelled", "interrupted")
    }
}

@Serializable
data class HermesUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0
)

@Serializable
data class HermesRuntime(
    val provider: String? = null,
    val model: String? = null,
    @SerialName("route_source") val routeSource: String? = null
)

@Serializable
data class HermesErrorInfo(
    val message: String? = null,
    val code: String? = null
)

/**
 * A pending human decision exposed on `GET /v1/runs/{id}` (`pending_controls`). The server uses
 * `prompt_id` for the identity and `execution_generation` for stale-generation detection. We
 * accept either `prompt_id` or `request_id` (some surfaces spell it differently) so the client
 * stays tolerant across server versions.
 */
@Serializable
data class HermesPendingControl(
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("prompt_id") val promptId: String? = null,
    @SerialName("execution_generation") val executionGeneration: Int = 0,
    val kind: String? = null,
    val tool: String? = null,
    val arguments: String? = null,
    val prompt: String? = null
) {
    /** Canonical identity to echo back to the approval endpoint. */
    val identity: String?
        get() = requestId ?: promptId
}

// ---- Request: POST /v1/runs/{id}/approval ----

@Serializable
data class HermesApprovalRequest(
    @SerialName("request_id") val requestId: String,
    @SerialName("execution_generation") val executionGeneration: Int,
    /** "once" runs the gated tool once; "deny" blocks it. */
    val choice: String
) {
    companion object {
        const val CHOICE_ONCE = "once"
        const val CHOICE_DENY = "deny"
    }
}

// ---- Response: POST /v1/runs/{id}/stop (and approval ack) ----

@Serializable
data class HermesStopResult(
    val status: String = ""
)

@Serializable
data class HermesApprovalResult(
    val status: String = "",
    val outcome: String? = null
)

// ---- GET /v1/capabilities ----

@Serializable
data class HermesCapabilities(
    @SerialName("object") val obj: String? = null,
    val platform: String? = null,
    val model: String? = null,
    val auth: HermesAuth? = null,
    val features: HermesFeatures = HermesFeatures(),
    val endpoints: HermesEndpoints = HermesEndpoints(),
    @SerialName("session_key_header") val sessionKeyHeader: String? = null
) {
    /** Whether the server advertises the Runs API at all. */
    val supportsRuns: Boolean
        get() = features.runSubmission && features.runStatus && features.runEventsSse
}

@Serializable
data class HermesAuth(
    val type: String? = null,
    val required: Boolean = false
)

@Serializable
data class HermesFeatures(
    @SerialName("chat_completions") val chatCompletions: Boolean = false,
    @SerialName("responses_api") val responsesApi: Boolean = false,
    @SerialName("run_submission") val runSubmission: Boolean = false,
    @SerialName("run_status") val runStatus: Boolean = false,
    @SerialName("run_events_sse") val runEventsSse: Boolean = false,
    @SerialName("run_stop") val runStop: Boolean = false,
    @SerialName("run_approval") val runApproval: Boolean = false,
    @SerialName("reasoning_streaming") val reasoningStreaming: Boolean = false,
    @SerialName("tool_progress_events") val toolProgressEvents: Boolean = false
)

@Serializable
data class HermesEndpoints(
    val skills: Boolean = false,
    val toolsets: Boolean = false
)

// ---- GET /health ----

@Serializable
data class HermesHealth(
    val status: String = ""
) {
    val isOk: Boolean get() = status.equals("ok", ignoreCase = true)
}

// ---- POST /v1/chat/completions (OpenAI-compatible minimal surface) ----

@Serializable
data class HermesChatRequest(
    val model: String = "hermes-agent",
    val messages: List<HermesChatMessage> = emptyList(),
    val stream: Boolean = false
)

@Serializable
data class HermesChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class HermesChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<HermesChoice> = emptyList()
)

@Serializable
data class HermesChoice(
    val index: Int = 0,
    val message: HermesChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)
