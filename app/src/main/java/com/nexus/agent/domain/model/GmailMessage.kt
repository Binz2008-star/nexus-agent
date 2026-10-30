package com.nexus.agent.domain.model

/**
 * One Gmail message hit from a read-only inbox search. [snippet] is a short excerpt and is
 * never logged; [from] and [subject] are shown as the source title.
 */
data class GmailMessage(
    val id: String,
    val threadId: String,
    val subject: String,
    val from: String,
    val snippet: String
)
