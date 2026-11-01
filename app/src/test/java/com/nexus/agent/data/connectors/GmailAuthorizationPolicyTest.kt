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
    @Test fun cachedScopesAloneAreNeverProofOfConnection() {
        val requested = listOf(GoogleScopePolicy.GMAIL_READ)
        val granted = setOf(GoogleScopePolicy.GMAIL_READ)
        assertFalse(GoogleRestorePolicy.canTrust(requested, granted,
            tokenPresent = false, needsResolution = false, accountMatches = true))
        assertFalse(GoogleRestorePolicy.canTrust(requested, granted,
            tokenPresent = true, needsResolution = true, accountMatches = true))
        assertFalse(GoogleRestorePolicy.canTrust(requested, granted,
            tokenPresent = true, needsResolution = false, accountMatches = false))
    }

    @Test fun silentReconnectRejectsEscalatedOrIncompleteGrants() {
        val requested = listOf(GoogleScopePolicy.GMAIL_READ, GoogleScopePolicy.DRIVE_READ)
        assertFalse(GoogleRestorePolicy.canTrust(
            requested, setOf(GoogleScopePolicy.GMAIL_READ),
            tokenPresent = true, needsResolution = false, accountMatches = true))
        assertFalse(GoogleRestorePolicy.canTrust(
            listOf("https://mail.google.com/"), setOf("https://mail.google.com/"),
            tokenPresent = true, needsResolution = false, accountMatches = true))
    }

    @Test fun silentReconnectOnlyAcceptsVerifiedMatchingAccountAndScopes() {
        assertTrue(GoogleRestorePolicy.canTrust(
            listOf(GoogleScopePolicy.GMAIL_READ),
            setOf(GoogleScopePolicy.GMAIL_READ, "openid", "email"),
            tokenPresent = true, needsResolution = false, accountMatches = true))
    }

}
