package com.nexus.agent.domain.hermes

/**
 * Domain model of a Hermes run's lifecycle, produced from the `/v1/runs/{id}/events` SSE stream
 * (and, for terminal states, from the status endpoint on reconnection).
 *
 * Kept in `domain` (not `data`) so the ViewModel/UI consume typed events without depending on the
 * raw SSE or the HTTP client.
 */
sealed interface HermesRunEvent {

    /** A token delta of the assistant's answer text. */
    data class Delta(val text: String) : HermesRunEvent

    /** Mid-turn assistant commentary (may already have been emitted as a delta). */
    data class Interim(val text: String, val alreadyStreamed: Boolean) : HermesRunEvent

    /** A tool started executing on the Hermes host. */
    data class ToolStarted(val tool: String, val preview: String?) : HermesRunEvent

    /** A tool finished. [error] reflects the tool's own outcome (denied approval, non-zero exit, …). */
    data class ToolCompleted(
        val tool: String,
        val durationSec: Double?,
        val error: Boolean,
        val preview: String?
    ) : HermesRunEvent

    /**
     * The run is waiting on a human decision. The UI must surface an approval card and then POST
     * `/v1/runs/{id}/approval` with [requestId] + [executionGeneration] and `choice` once/deny.
     */
    data class ApprovalRequested(
        val requestId: String?,
        val executionGeneration: Int,
        val tool: String?,
        val arguments: String?,
        val prompt: String?
    ) : HermesRunEvent

    /** Terminal: the run finished with [output] as the final answer text. */
    data class Completed(val output: String) : HermesRunEvent

    /** Terminal: the run failed. */
    data class Failed(val message: String?) : HermesRunEvent

    /** Terminal: the run was cancelled (including via stop). */
    data class Cancelled(val message: String?) : HermesRunEvent

    /** Terminal: the run was interrupted (e.g. gateway shutdown). */
    data class Interrupted(val message: String?) : HermesRunEvent

    /** An unrecognized or informational event. Carries the raw event type for diagnostics. */
    data class Unknown(val type: String, val raw: String) : HermesRunEvent

    /** True for events that end the run (no further progress expected). */
    val isTerminal: Boolean
        get() = this is Completed || this is Failed || this is Cancelled || this is Interrupted
}
