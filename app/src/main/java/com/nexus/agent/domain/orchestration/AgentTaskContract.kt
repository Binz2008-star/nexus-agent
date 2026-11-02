package com.nexus.agent.domain.orchestration

/**
 * A task contract supplied by trusted caller code, never by a model response or retrieved text.
 *
 * The default keeps ordinary chat behaviour unchanged. For an action task, a model answer
 * is not sufficient until a tool actually executes successfully. Verification of an
 * external outcome requires a separate trusted postcondition check.
 *
 * Inspired by Personal AI Core ADR-023; this is a bounded Android adaptation, not a
 * wholesale port of its Python agent.
 */
data class AgentTaskContract(
    val actionRequired: Boolean = false,
    val independentVerificationRequired: Boolean = false,
    val maxFailedActions: Int = 3,
    val maxActions: Int = 12,
    /** Optional exact action identity, set only by trusted caller (not model text). */
    val requiredToolName: String? = null
) {
    init {
        require(!independentVerificationRequired || actionRequired) {
            "independent verification requires an action task"
        }
        require(maxFailedActions in 1..3) { "failure budget cannot exceed 3" }
        require(maxActions in 1..12) { "action budget cannot exceed 12" }
        require(requiredToolName == null || (actionRequired && requiredToolName.isNotBlank())) {
            "required tool name requires an action task"
        }
    }
}

/**
 * Minimal audit metadata: never include tool arguments, source content, secrets, or PII.
 * EXECUTED means ToolResult.Success, NOT that an external postcondition was independently checked.
 */
enum class ActionEvidenceStatus { EXECUTED, VERIFIED, FAILED, BLOCKED }

data class ActionEvidence(val toolName: String, val status: ActionEvidenceStatus)
