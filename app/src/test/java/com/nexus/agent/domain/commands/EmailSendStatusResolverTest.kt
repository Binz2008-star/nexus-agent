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
}
