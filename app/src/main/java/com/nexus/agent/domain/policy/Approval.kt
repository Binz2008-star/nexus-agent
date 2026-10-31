package com.nexus.agent.domain.policy

/**
 * An action that requires explicit user approval before execution. Bound to an id, tool, and
 * (optionally) the account it would act on.
 */
data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val arguments: String,
    val accountId: String? = null
)

/**
 * Proof of approval for a specific request. Time-boxed — expires after [ApprovalGate.approvalTtlMs].
 * Never carries secret material.
 */
data class ApprovalReceipt(
    val requestId: String,
    val approvedAtMs: Long,
    val expiresAtMs: Long
) {
    fun isValid(nowMs: Long): Boolean = nowMs < expiresAtMs
}

/**
 * Issues and validates time-boxed, one-shot approvals. This is the policy core that lets a
 * model *propose* a COMMIT/PREPARE action while only the user (or a policy engine) *authorizes*
 * it via a receipt. Replay-resistant: a consumed or expired receipt cannot be reused, and a
 * receipt is bound to the specific tool + arguments it was issued for (so an approval for one
 * action cannot authorize a different one).
 *
 * Pure and unit-testable — time is injected via [clock]. Methods are synchronized so a gate can
 * be issued on one thread (UI) and consumed on another (the tool loop) without a data race.
 */
class ApprovalGate(
    private val approvalTtlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    // What a receipt authorizes + when it expires.
    private data class Grant(val toolName: String, val arguments: String, val expiryMs: Long)

    private val grants = mutableMapOf<String, Grant>()
    private val consumed = mutableSetOf<String>()

    /** Records approval for [request] and returns a receipt the executor must present. */
    @Synchronized
    fun approve(request: ApprovalRequest): ApprovalReceipt {
        val now = clock()
        val expiry = now + approvalTtlMs
        grants[request.id] = Grant(request.toolName, request.arguments, expiry)
        return ApprovalReceipt(request.id, now, expiry)
    }

    /** Records rejection — removes any pending grant so it can never be authorized. */
    @Synchronized
    fun reject(request: ApprovalRequest) {
        grants.remove(request.id)
    }

    /** True only for a valid, unconsumed, unexpired receipt that matches the tool + arguments. */
    @Synchronized
    fun isAuthorized(receipt: ApprovalReceipt, toolName: String, arguments: String): Boolean {
        if (receipt.requestId in consumed) return false
        val grant = grants[receipt.requestId] ?: return false
        if (grant.toolName != toolName || grant.arguments != arguments) return false
        if (clock() >= grant.expiryMs) {
            grants.remove(receipt.requestId)
            return false
        }
        return true
    }

    /** Marks a receipt as used so the same approval cannot be replayed. */
    @Synchronized
    fun consume(receipt: ApprovalReceipt) {
        consumed.add(receipt.requestId)
        grants.remove(receipt.requestId)
    }

    companion object {
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L
    }
}
