package com.nexus.agent.domain.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Only draft fields and a lifecycle marker; NEVER serialize an ApprovalReceipt,
 * OAuth credential or a replayable Gmail request.
 */
interface EmailDraftStore {
    suspend fun load(): PendingEmailDraft?
    suspend fun save(draft: PendingEmailDraft)
    suspend fun clear()
}

object EmailDraftCodec {
    fun encode(draft: PendingEmailDraft): String = buildJsonObject {
        put("version", JsonPrimitive(1))
        put("recipient", JsonPrimitive(draft.recipient.orEmpty()))
        put("subject", JsonPrimitive(draft.subject.orEmpty()))
        put("body", JsonPrimitive(draft.body.orEmpty()))
        put("status", JsonPrimitive(draft.status.name))
        put("conversationId", JsonPrimitive(draft.conversationId ?: -1L))
        put("revision", JsonPrimitive(draft.revision))
    }.toString()

    fun decode(text: String): PendingEmailDraft? = runCatching {
        if (text.length > 100_000) return null
        val obj = Json.parseToJsonElement(text) as? JsonObject ?: return null
        if (obj["version"]?.jsonPrimitive?.contentOrNull != "1") return null
        val conv = obj["conversationId"]?.jsonPrimitive?.longOrNull ?: return null
        if (conv <= 0) return null
        val revision = obj["revision"]?.jsonPrimitive?.longOrNull ?: return null
        if (revision < 0) return null
        val raw = obj["status"]?.jsonPrimitive?.contentOrNull ?: return null
        val stored = EmailSendStatus.entries.firstOrNull { it.name == raw } ?: return null
        // A process can die after Gmail accepts a POST but before the app receives its
        // message ID. Never automatically resume SENDING or AWAITING_APPROVAL.
        val safeStatus = when (stored) {
            EmailSendStatus.SENDING -> EmailSendStatus.UNKNOWN
            EmailSendStatus.AWAITING_APPROVAL -> EmailSendStatus.DRAFT
            else -> stored
        }
        PendingEmailDraft(
            recipient = obj["recipient"]?.jsonPrimitive?.contentOrNull,
            subject = obj["subject"]?.jsonPrimitive?.contentOrNull,
            body = obj["body"]?.jsonPrimitive?.contentOrNull,
            status = safeStatus,
            conversationId = conv,
            revision = revision
        )
    }.getOrNull()
}
