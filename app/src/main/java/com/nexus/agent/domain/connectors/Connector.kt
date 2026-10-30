package com.nexus.agent.domain.connectors

import kotlinx.coroutines.flow.StateFlow

/**
 * A third-party account the assistant can plug into (Google Drive, Gmail, …).
 *
 * Connectors are pure domain contracts: they expose connection state and a way to obtain a
 * short-lived access token, but the OAuth flow + encrypted token storage live in the data
 * layer ([GoogleAuthSession] / `GoogleAuthManager`).
 */
sealed interface Connector {
    /** Stable id, e.g. "google_drive". */
    val id: String

    /** Human-readable name for the settings UI, e.g. "Google Drive". */
    val displayName: String

    /** OAuth scopes this connector requests (read-only, no mutation scopes). */
    val scopes: List<String>

    /** Reactive connection state (true once a refresh token is stored). */
    val isConnected: StateFlow<Boolean>

    /** The connected account's email, or null. */
    val email: StateFlow<String?>

    /** Returns a fresh access token for this connector's scopes, or null if not connected. */
    suspend fun accessToken(): String?

    /** Revokes the local session (clears the stored token); no server-side revocation. */
    suspend fun disconnect()
}
