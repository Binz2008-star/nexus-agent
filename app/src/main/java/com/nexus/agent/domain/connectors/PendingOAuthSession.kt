package com.nexus.agent.domain.connectors

/**
 * An in-flight OAuth authorization (state + PKCE verifier + creation time). Persisted so a
 * cold-start redirect (after the process was killed while the Custom Tab was open) can still
 * complete the token exchange.
 *
 * Pure + unit-testable: [matchesState] validates the CSRF state and [isExpired] bounds the
 * window during which a redirect is accepted (replay protection).
 */
data class PendingOAuthSession(
    val state: String,
    val verifier: String,
    val createdAtMs: Long
) {
    fun matchesState(incoming: String?): Boolean = incoming != null && incoming == state

    fun isExpired(nowMs: Long, ttlMillis: Long): Boolean = nowMs - createdAtMs > ttlMillis
}
