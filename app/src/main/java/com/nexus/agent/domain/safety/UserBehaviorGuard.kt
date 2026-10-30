package com.nexus.agent.domain.safety

/**
 * Escalating behavior guard. Ported from Mashaaer's `user_behavior_guard.py`: tracks
 * bad-intent violations per session and escalates warn -> block with time-based expiry.
 * Time is injected via [clock] so tests are deterministic.
 */
class UserBehaviorGuard(
    private val warningThreshold: Int = 2,
    private val blockThreshold: Int = 4,
    private val blockDurationMillis: Long = 15 * 60 * 1000L,
    private val intentExpiryMillis: Long = 30 * 60 * 1000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    sealed interface BehaviorDecision {
        data object Allowed : BehaviorDecision
        data class Warned(val warningCount: Int) : BehaviorDecision
        data class Blocked(val remainingMillis: Long) : BehaviorDecision
    }

    // sessionId -> blockUntilMillis
    private val blockedUsers = mutableMapOf<String, Long>()
    // sessionId -> list of violation timestamps (within the expiry window)
    private val badIntentsLog = mutableMapOf<String, MutableList<Long>>()

    /** True while a session is inside an active block window. */
    fun isUserBlocked(sessionId: String): Boolean {
        val blockUntil = blockedUsers[sessionId] ?: return false
        if (clock() < blockUntil) return true
        blockedUsers.remove(sessionId) // expired
        return false
    }

    /** Milliseconds until a session's block expires (0 if not blocked). */
    fun getRemainingBlockTimeMillis(sessionId: String): Long {
        val blockUntil = blockedUsers[sessionId] ?: return 0L
        return (blockUntil - clock()).coerceAtLeast(0L)
    }

    /**
     * Records a bad-intent violation and returns the escalated action:
     * Allowed (below warning threshold), Warned (at/above warning), or Blocked
     * (at/above block threshold).
     */
    fun registerBadIntent(sessionId: String): BehaviorDecision {
        if (isUserBlocked(sessionId)) {
            return BehaviorDecision.Blocked(getRemainingBlockTimeMillis(sessionId))
        }

        val now = clock()
        val timestamps = badIntentsLog.getOrPut(sessionId) { mutableListOf() }
        timestamps.add(now)

        // Expire violations older than the expiry window.
        val cutoff = now - intentExpiryMillis
        timestamps.removeAll { it < cutoff }

        if (timestamps.size >= blockThreshold) {
            blockedUsers[sessionId] = now + blockDurationMillis
            badIntentsLog.remove(sessionId)
            return BehaviorDecision.Blocked(blockDurationMillis)
        }

        if (timestamps.size >= warningThreshold) {
            return BehaviorDecision.Warned(timestamps.size)
        }

        return BehaviorDecision.Allowed
    }

    /** Clears a session's violations and any active block. */
    fun reset(sessionId: String) {
        blockedUsers.remove(sessionId)
        badIntentsLog.remove(sessionId)
    }
}
