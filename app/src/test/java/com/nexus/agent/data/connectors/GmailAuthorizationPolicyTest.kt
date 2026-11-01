package com.nexus.agent.data.connectors

import com.nexus.agent.domain.connectors.GmailConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailAuthorizationPolicyTest {
    private val readonly = GmailConnector.GMAIL_READONLY_SCOPE

    @Test fun onlyGmailReadScopeCanBeRequested() {
        val result = GmailAuthorizationPolicy.allowedScopes(
            listOf(readonly, "https://mail.google.com/", readonly, "openid")
        )
        assertEquals(listOf(readonly), result)
    }

    @Test fun doesNotPermitSendModifyOrFullMailboxScope() {
        assertTrue(GmailAuthorizationPolicy.allowedScopes(
            listOf("https://www.googleapis.com/auth/gmail.modify")
        ).isEmpty())
        assertTrue(GmailAuthorizationPolicy.allowedScopes(
            listOf("https://www.googleapis.com/auth/gmail.send")
        ).isEmpty())
        assertTrue(GmailAuthorizationPolicy.allowedScopes(
            listOf("https://mail.google.com/")
        ).isEmpty())
    }

    @Test fun missingGrantCannotBeReportedAsConnected() {
        assertFalse(GmailAuthorizationPolicy.hasRequiredGrant(null))
        assertFalse(GmailAuthorizationPolicy.hasRequiredGrant(emptyList()))
        assertFalse(GmailAuthorizationPolicy.hasRequiredGrant(listOf("openid", "email")))
        assertFalse(GmailAuthorizationPolicy.hasRequiredGrant(
            listOf("https://www.googleapis.com/auth/gmail.send")
        ))
    }

    @Test fun exactGmailReadonlyGrantRequired() {
        assertTrue(GmailAuthorizationPolicy.hasRequiredGrant(listOf(readonly)))
        assertTrue(GmailAuthorizationPolicy.hasRequiredGrant(listOf("email", readonly)))
    }
}
