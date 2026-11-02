package com.nexus.agent.domain.commands

import com.nexus.agent.domain.orchestration.ActionEvidence
import com.nexus.agent.domain.orchestration.ActionEvidenceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailSendStatusResolverTest {
    private fun ev(tool: String, status: ActionEvidenceStatus) = ActionEvidence(tool, status)

    @Test fun executedGmailSendIsAccepted() {
        assertEquals(
            EmailSendStatus.GMAIL_ACCEPTED,
            EmailSendStatusResolver.resolve(
                listOf(ev("gmail_send", ActionEvidenceStatus.EXECUTED))
            )
        )
    }

    @Test fun independentlyVerifiedSendIsAccepted() {
        assertEquals(
            EmailSendStatus.GMAIL_ACCEPTED,
            EmailSendStatusResolver.resolve(
                listOf(ev("gmail_send", ActionEvidenceStatus.VERIFIED))
            )
        )
    }

    @Test fun failedGmailSendIsNotAccepted() {
        assertEquals(
            EmailSendStatus.FAILED,
            EmailSendStatusResolver.resolve(
                listOf(ev("gmail_send", ActionEvidenceStatus.FAILED))
            )
        )
    }

    @Test fun blockedApprovalIsNotAccepted() {
        assertEquals(
            EmailSendStatus.FAILED,
            EmailSendStatusResolver.resolve(
                listOf(ev("gmail_send", ActionEvidenceStatus.BLOCKED))
            )
        )
    }

    @Test fun noGmailSendEvidenceIsUnknown() {
        assertEquals(EmailSendStatus.UNKNOWN, EmailSendStatusResolver.resolve(emptyList()))
        assertEquals(
            EmailSendStatus.UNKNOWN,
            EmailSendStatusResolver.resolve(
                listOf(ev("gmail_search", ActionEvidenceStatus.EXECUTED))
            )
        )
    }

    @Test fun anyAcceptedSendWinsOverAPriorFailure() {
        assertEquals(
            EmailSendStatus.GMAIL_ACCEPTED,
            EmailSendStatusResolver.resolve(
                listOf(
                    ev("gmail_send", ActionEvidenceStatus.FAILED),
                    ev("gmail_send", ActionEvidenceStatus.EXECUTED)
                )
            )
        )
    }

    @Test fun statusLinesAreTruthful() {
        assertTrue(EmailSendStatusResolver.statusLine(EmailSendStatus.GMAIL_ACCEPTED).contains("قبول"))
        assertTrue(EmailSendStatusResolver.statusLine(EmailSendStatus.FAILED).contains("لم تُرسل"))
        assertTrue(
            EmailSendStatusResolver.statusLine(EmailSendStatus.UNKNOWN).contains("لا يمكن تأكيد")
        )
    }

    @Test fun draftCompletenessRequiresAllThreeFields() {
        assertFalse(PendingEmailDraft(recipient = "a@b.com").isComplete)
        assertFalse(
            PendingEmailDraft(recipient = "a@b.com", subject = "Hi", body = "").isComplete
        )
        assertTrue(
            PendingEmailDraft(recipient = "a@b.com", subject = "Hi", body = "Body").isComplete
        )
    }
    @Test fun failedLoopNeverPersistsFabricatedSentClaim() {
        val reply = EmailSendResponsePolicy.response(
            emptyList(),
            "The email was sent successfully to your customer"
        )
        assertTrue(reply.contains("لا يمكن تأكيد"))
        assertFalse(reply.contains("The email was sent"))
        assertEquals(EmailSendStatus.UNKNOWN, EmailSendResponsePolicy.status(emptyList()))
    }

    @Test fun modelCannotClaimDeliveryAfterSuccessfulGmailSend() {
        val result = EmailSendResponsePolicy.response(
            listOf(ev("gmail_send", ActionEvidenceStatus.EXECUTED)),
            "The email was delivered and read"
        )
        assertTrue(result.contains("Gmail"))
        assertFalse(result.contains("delivered"))
    }

    @Test fun restoredUnfinishedDraftNeverReplaysItsApproval() {
        val draft = PendingEmailDraft(
            "a@example.com", "Subject", "Body",
            status = EmailSendStatus.SENDING, conversationId = 9L, revision = 3
        )
        val restored = EmailDraftCodec.decode(EmailDraftCodec.encode(draft))
        assertEquals(EmailSendStatus.UNKNOWN, restored?.status)
        assertEquals(3L, restored?.revision)
        assertEquals(9L, restored?.conversationId)
        assertEquals("Body", restored?.body)
    }

    @Test fun approvalMustBeRequestedAgainAfterProcessDeath() {
        val draft = PendingEmailDraft(
            "a@example.com", "Subject", "Body",
            status = EmailSendStatus.AWAITING_APPROVAL, conversationId = 19L
        )
        assertEquals(
            EmailSendStatus.DRAFT,
            EmailDraftCodec.decode(EmailDraftCodec.encode(draft))?.status
        )
    }

    @Test fun malformedOrStaleDraftCannotRestore() {
        assertEquals(null, EmailDraftCodec.decode("malformed"))
        assertEquals(null, EmailDraftCodec.decode("{\"version\":2}"))
        assertEquals(null, EmailDraftCodec.decode("x".repeat(100_001)))
    }

}
