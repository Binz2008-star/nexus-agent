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
    val status: EmailSendStatus = EmailSendStatus.DRAFT,
    /** Drafts remain bound to their source conversation across process restarts. */
    val conversationId: Long? = null,
    val revision: Long = 0L
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
            // Definitive tool failure or denied approval is NOT provider acceptance.
            send.any { it.status == ActionEvidenceStatus.BLOCKED } ->
                EmailSendStatus.FAILED
            // HTTP timeouts or an interrupted response cannot prove Gmail rejected a
            // request. UNKNOWN requires checking Sent before any new approved POST.
            send.any { it.status == ActionEvidenceStatus.FAILED } ->
                EmailSendStatus.UNKNOWN
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

/**
 * Final, model-independent response for a send intent. Never append a model answer
 * that might falsely claim Gmail accepted or delivered a message. If a tool loop
 * fails after Gmail accepted, preserve the explicit provider-side evidence.
 */
object EmailSendResponsePolicy {
    fun status(evidence: List<ActionEvidence>): EmailSendStatus =
        EmailSendStatusResolver.resolve(evidence)

    fun response(evidence: List<ActionEvidence>, untrustedModelAnswer: String? = null): String {
        // The untrusted answer is deliberately ignored for sensitive send completion.
        return EmailSendStatusResolver.statusLine(status(evidence))
    }
}
