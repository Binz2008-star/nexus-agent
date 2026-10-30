package com.nexus.agent.domain.connectors

/** Read-only Gmail connector (read mail metadata; no send/delete). */
class GmailConnector(session: GoogleAuthSession) : GoogleConnector(session) {
    override val id: String = "google_gmail"
    override val displayName: String = "Gmail"
    override val scopes: List<String> = listOf(GMAIL_READONLY_SCOPE)

    companion object {
        const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    }
}
