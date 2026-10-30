package com.nexus.agent.domain.connectors

import kotlinx.coroutines.flow.StateFlow

/**
 * Auth session backing a Google connector. Implemented by the data-layer
 * `GoogleAuthManager` (AppAuth + encrypted token storage); kept as an interface so the
 * domain never depends on AppAuth or Android.
 */
interface GoogleAuthSession {
    val isConnected: StateFlow<Boolean>
    val email: StateFlow<String?>
    suspend fun accessToken(): String?
    suspend fun disconnect()
}

/**
 * Common base for all Google connectors (Drive, Gmail, …). Concrete subclasses declare only
 * their [id], [displayName], and OAuth [scopes]; the OAuth lifecycle is delegated to the
 * shared [session].
 */
abstract class GoogleConnector(
    protected val session: GoogleAuthSession
) : Connector {

    abstract override val id: String
    abstract override val displayName: String

    /** OAuth scopes this connector requires (read-only, no mutation scopes). */
    abstract override val scopes: List<String>

    override val isConnected: StateFlow<Boolean> get() = session.isConnected
    override val email: StateFlow<String?> get() = session.email
    override suspend fun accessToken(): String? = session.accessToken()
    override suspend fun disconnect() = session.disconnect()
}
