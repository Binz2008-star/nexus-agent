package com.nexus.agent.domain.commands

import com.nexus.agent.domain.orchestration.ActionEvidence
import com.nexus.agent.domain.orchestration.ActionEvidenceStatus

/**
 * Lifecycle of a Gmail send, surfaced to the user as structured, evidence-based status instead of
 * trusting the model's narrative ("sent", "done", …). The transition into [GMAIL_ACCEPTED] or
 * [FAILED] is driven ONLY by the `gmail_send` tool's [ActionEvidence], never by model text.
 */
enum class EmailSendStatus { DRAFT, AWAITING_APPROVAL, SENDING, GMAIL_ACCEPTED, FAILED, UNKNOWN }

/**
 * In-memory email draft held across turns so NEXUS does not repeatedly re-ask the recipient,
 * subject and body the user already supplied. Never persisted to analytics or logs.
 */
data class PendingEmailDraft(
    val recipient: String? = null,
    val subject: String? = null,
    val body: String? = null,
    val status: EmailSendStatus = EmailSendStatus.DRAFT
) {
    val isComplete: Boolean
        get() = !recipient.isNullOrBlank() && !subject.isNullOrBlank() && !body.isNullOrBlank()
}

/**
 * Maps the tool-loop's audit evidence for the `gmail_send` tool onto a trustworthy status.
 * EXECUTED means the Gmail API returned a message id (ToolResult.Success) — the message was
 * accepted for delivery, which is all Gmail guarantees (it does not confirm final delivery).
 */
object EmailSendStatusResolver {
    const val GMAIL_SEND_TOOL = "gmail_send"

    fun resolve(evidence: List<ActionEvidence>): EmailSendStatus {
        val send = evidence.filter { it.toolName == GMAIL_SEND_TOOL }
        if (send.isEmpty()) return EmailSendStatus.UNKNOWN
        return when {
            send.any { it.status == ActionEvidenceStatus.VERIFIED || it.status == ActionEvidenceStatus.EXECUTED } ->
                EmailSendStatus.GMAIL_ACCEPTED
            // Failed (API error) or blocked (approval not granted) are both NOT sent.
            send.any { it.status == ActionEvidenceStatus.FAILED || it.status == ActionEvidenceStatus.BLOCKED } ->
                EmailSendStatus.FAILED
            else -> EmailSendStatus.UNKNOWN
        }
    }

    /** A short, user-facing, truthfully-phrased status line in Arabic. */
    fun statusLine(status: EmailSendStatus): String = when (status) {
        EmailSendStatus.GMAIL_ACCEPTED -> "✓ تم قبول الرسالة من Gmail."
        EmailSendStatus.FAILED -> "لم تُرسل الرسالة — لم يؤكد Gmail قبولها."
        EmailSendStatus.AWAITING_APPROVAL -> "بانتظار موافقتك على الإرسال."
        EmailSendStatus.SENDING -> "جارٍ الإرسال…"
        EmailSendStatus.DRAFT -> "مسودة محفوظة (لم تُرسل بعد)."
        EmailSendStatus.UNKNOWN -> "لا يمكن تأكيد الإرسال — لم تُسجَّل نتيجة إرسال موثوقة."
    }
}
