package com.nexus.agent.domain.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailIntentRouterTest {
    @Test fun bareServiceNamesShowActualConnectionStatus() {
        listOf("gmail", "GMAIL", "gmail?", "inbox", "email", "جيميل", "جي ميل",
            "البريد", "الإيميل").forEach {
            assertEquals(it, EmailIntent.STATUS, EmailIntentRouter.classify(it))
            assertEquals(it, Command.Email, CommandDetector.detect(it))
        }
    }

    @Test fun explicitReadCommandsSearchRealMailbox() {
        listOf(
            "check my email", "search my inbox for invoice", "read Gmail",
            "تفقد بريدي", "اقرأ رسائلي", "اعرض البريد", "ابحث في جيميل",
            "راجع رسائل البريد"
        ).forEach {
            assertEquals(it, EmailIntent.READ, EmailIntentRouter.classify(it))
        }
    }

    @Test fun sendAndDraftCannotBeMisroutedToSearch() {
        listOf(
            "send email to abc@example.com",
            "draft a Gmail response", "reply to my email",
            "اكتب ايميل لشركة", "أرسل بريدًا للعميل",
            "افحص الإيميل ثم ابعث رد"
        ).forEach {
            assertEquals(it, EmailIntent.AGENT, EmailIntentRouter.classify(it))
        }
    }

    @Test fun unrecognizedEmailRequestsGoToAgentNotBlindSearch() {
        assertEquals(EmailIntent.AGENT, EmailIntentRouter.classify("ما الفرق بين Gmail وOutlook؟"))
        assertEquals(EmailIntent.AGENT, EmailIntentRouter.classify("how does gmail work"))
    }

    @Test fun statusIsDerivedFromActualGrantNotInvented() {
        val notConnected = EmailTaskMessages.connectionStatus(false)
        val connected = EmailTaskMessages.connectionStatus(true)
        assertTrue(notConnected.contains("غير متصل"))
        assertTrue(notConnected.contains("Connect"))
        assertFalse(connected.contains("غير متصل"))
        assertTrue(connected.contains("صلاحية القراءة"))
    }

    @Test fun apiFailuresNeverGetConvertedIntoGenericAnswers() {
        for (code in listOf("auth", "no_token", "network", "server", "timeout", "missing_scope")) {
            val response = EmailTaskMessages.searchFailure(code)
            assertTrue(response.contains("Gmail") || response.contains("البحث"))
            assertFalse(response.contains("افتح تطبيق Gmail"))
        }
        assertTrue(EmailTaskMessages.NO_MATCHES.contains("لم أجد"))
    }
}
