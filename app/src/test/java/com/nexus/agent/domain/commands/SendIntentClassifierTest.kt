package com.nexus.agent.domain.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class SendIntentClassifierTest {
    @Test fun realEmailSendsAreClassifiedAsSend() {
        listOf(
            "send email to abc@example.com",
            "send an email to the client about the invoice",
            "forward this email to john@example.com",
            "أرسل بريدًا للعميل",
            "ابعث ايميل للشركة",
            "افحص الإيميل ثم ابعث رد",
            "send to robenedwan.1@gmail.com"
        ).forEach {
            assertEquals(it, SendIntent.SEND, SendIntentClassifier.classify(it))
        }
    }

    @Test fun draftingIsNotASend() {
        listOf(
            "draft a Gmail response",
            "write an email to the team",
            "reply to my email",
            "compose a message by email",
            "اكتب ايميل لشركة",
            "جهّز بريدًا للعميل"
        ).forEach {
            assertEquals(it, SendIntent.WRITE, SendIntentClassifier.classify(it))
        }
    }

    @Test fun sendVerbWinsOverWriteVerb() {
        assertEquals(SendIntent.SEND, SendIntentClassifier.classify("send the draft email"))
        assertEquals(SendIntent.SEND, SendIntentClassifier.classify("write and send the email"))
    }

    @Test fun nonEmailSendRequestsAreNotClassified() {
        // A generic "send" with no email noun / address must not force an action contract.
        listOf(
            "send me the file",
            "send me a message",
            "hello",
            "ما الطقس اليوم؟",
            "open settings"
        ).forEach {
            assertEquals(it, SendIntent.NONE, SendIntentClassifier.classify(it))
        }
    }

    @Test fun emailAddressAloneEstablishesEmailContext() {
        assertEquals(SendIntent.SEND, SendIntentClassifier.classify("send it to john@example.com"))
    }
}
