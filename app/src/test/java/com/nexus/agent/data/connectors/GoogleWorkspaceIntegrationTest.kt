package com.nexus.agent.data.connectors

import com.nexus.agent.data.remote.GmailMessageComposer
import com.nexus.agent.data.remote.GmailSendApiClient
import com.nexus.agent.domain.tool.GmailSendTool
import com.nexus.agent.domain.tool.GoogleDriveCreateTool
import com.nexus.agent.domain.tool.GoogleWorkspaceApiClient
import com.nexus.agent.domain.tool.ToolOrchestrator
import com.nexus.agent.domain.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class GoogleWorkspaceIntegrationTest {

    @Test fun serviceScopesAreIndividuallyAuthorizedAndBroadScopesDenied() {
        assertTrue(GoogleScopePolicy.isAllowedRequest(listOf(
            GoogleScopePolicy.GMAIL_SEND,
            GoogleScopePolicy.DRIVE_FILE,
            GoogleScopePolicy.CALENDAR_READ,
            GoogleScopePolicy.CONTACTS_READ,
            GoogleScopePolicy.TASKS_READ
        )))
        assertFalse(GoogleScopePolicy.isAllowedRequest(emptyList()))
        assertFalse(GoogleScopePolicy.isAllowedRequest(listOf("https://mail.google.com/")))
        assertFalse(GoogleScopePolicy.isAllowedRequest(listOf(
            "https://www.googleapis.com/auth/drive"
        )))
        assertFalse(GoogleScopePolicy.isAllowedRequest(listOf(
            GoogleScopePolicy.GMAIL_READ, "https://www.googleapis.com/auth/gmail.modify"
        )))
        assertFalse(GoogleScopePolicy.hasRequiredGrant(listOf(GoogleScopePolicy.GMAIL_READ),
            listOf(GoogleScopePolicy.GMAIL_SEND)))
        assertTrue(GoogleScopePolicy.hasRequiredGrant(listOf(GoogleScopePolicy.GMAIL_READ,
            GoogleScopePolicy.GMAIL_SEND), listOf(GoogleScopePolicy.GMAIL_SEND)))
    }

    @Test fun mimeComposerSupportsArabicAndProperHeaders() {
        val raw = GmailMessageComposer.compose("recipient@example.com", "مرحبا", "رسالة تجريبية")!!
        val message = String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8)
        assertTrue(message.startsWith("To: recipient@example.com\r\n"))
        assertTrue(message.contains("\r\nMIME-Version: 1.0\r\n"))
        assertTrue(message.contains("\r\n\r\n"))
        assertTrue(message.contains("Subject: =?UTF-8?B?"))
    }

    @Test fun messageComposerRejectsHeaderInjectionAndInvalidRecipients() {
        assertEquals(null, GmailMessageComposer.compose("person@example.com\r\nBcc:evil@example.com", "s", "b"))
        assertEquals(null, GmailMessageComposer.compose("person@example.com", "hello\nBcc:evil", "b"))
        assertEquals(null, GmailMessageComposer.compose("no-at-sign", "subject", "body"))
        assertEquals(null, GmailMessageComposer.compose("x@example.com", "subject", ""))
    }

    @Test fun userApprovalMandatoryForGmailSendEvenIfScopeIsGranted() = runTest {
        val tool = GmailSendTool(GmailSendApiClient(tokenProvider = { "not-used" }))
        val orchestrator = ToolOrchestrator(mapOf(tool.name to tool))
        val result = orchestrator.execute(
            tool.name, buildJsonObject {
                put("to", "a@example.com")
                put("subject", "Hello")
                put("body", "Test")
            }, grantedScopes = setOf(GoogleScopePolicy.GMAIL_SEND)
        )
        assertTrue(result is ToolResult.Blocked)
    }

    @Test fun driveCreateAlsoRequiresExplicitApproval() = runTest {
        val api = GoogleWorkspaceApiClient(tokenFor = { "not-used" })
        val tool = GoogleDriveCreateTool(api)
        val result = ToolOrchestrator(mapOf(tool.name to tool))
            .execute(tool.name, buildJsonObject {
                put("name", "A new Sheet")
                put("type", "sheet")
            }, grantedScopes = setOf(GoogleScopePolicy.DRIVE_FILE))
        assertTrue(result is ToolResult.Blocked)
    }

    @Test fun apiRejectsNonGoogleHostsBeforeRequestingTokens() = runTest {
        var consultedTokens = false
        val api = GoogleWorkspaceApiClient(tokenFor = {
            consultedTokens = true
            "not-used"
        })
        val result = api.get("https://example.org/steal".toHttpUrl(),
            listOf(GoogleScopePolicy.DRIVE_READ))
        assertTrue(result.isFailure)
        assertFalse(consultedTokens)
    }
}
