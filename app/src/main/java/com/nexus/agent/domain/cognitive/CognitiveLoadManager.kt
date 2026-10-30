package com.nexus.agent.domain.cognitive

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Result of asking [CognitiveLoadManager] for permission to run one request.
 */
sealed interface CognitiveLoadDecision {
    data object Allowed : CognitiveLoadDecision

    /** The request was suppressed. [message] is a polite local reply for the UI. */
    data class Throttled(
        val message: String,
        val retryAfterMillis: Long
    ) : CognitiveLoadDecision
}

/**
 * Protects the orchestrator/network layer from being overwhelmed by user spam.
 *
 * Uses a state-backed sliding-window rate limiter (deterministic and unit-testable, unlike a
 * pure [kotlinx.coroutines.flow.debounce] pipeline): if more than [maxMessagesPerWindow]
 * messages arrive within [windowMillis], the manager refuses further requests for
 * [cooldownMillis] and returns a polite Arabic warning instead of hitting the network.
 *
 * Time is injected via [clock] (millis, monotonic) so tests can advance it deterministically.
 */
class CognitiveLoadManager(
    private val maxMessagesPerWindow: Int = 5,
    private val windowMillis: Long = 10_000L,
    private val cooldownMillis: Long = 4_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val throttleMessage: String = DEFAULT_THROTTLE_MESSAGE
) {
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Long>()
    private var blockedUntilMillis = 0L

    /**
     * Requests permission to execute one message. Returns [CognitiveLoadDecision.Allowed] when
     * the request may proceed, or [CognitiveLoadDecision.Throttled] when it must be suppressed.
     */
    suspend fun tryAcquire(): CognitiveLoadDecision = mutex.withLock {
        val now = clock()

        // Still inside a cooldown window from a previous burst.
        if (now < blockedUntilMillis) {
            return@withLock CognitiveLoadDecision.Throttled(throttleMessage, blockedUntilMillis - now)
        }

        // Drop timestamps older than the sliding window.
        while (timestamps.isNotEmpty() && now - timestamps.first() >= windowMillis) {
            timestamps.removeFirst()
        }

        if (timestamps.size >= maxMessagesPerWindow) {
            // Clear the window so the cooldown provides a genuine pause and [retryAfterMillis]
            // (cooldownMillis) stays accurate; otherwise the still-full window would re-throttle
            // the very next call once the cooldown expires.
            timestamps.clear()
            blockedUntilMillis = now + cooldownMillis
            return@withLock CognitiveLoadDecision.Throttled(throttleMessage, cooldownMillis)
        }

        timestamps.addLast(now)
        CognitiveLoadDecision.Allowed
    }

    /** Clears the window and any active cooldown (e.g. after a successful reset/relogin). */
    suspend fun reset() = mutex.withLock {
        timestamps.clear()
        blockedUntilMillis = 0L
    }

    /** True while a cooldown is active. */
    suspend fun isThrottled(): Boolean = mutex.withLock { clock() < blockedUntilMillis }

    companion object {
        const val DEFAULT_THROTTLE_MESSAGE = "أرجو الانتظار قليلاً حتى أنتهي من التفكير…"
    }
}
