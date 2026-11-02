package com.nexus.agent.domain.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnowledgeSearchIntentTest {
    @Test fun extractsEnglishKnowledgeSearch() {
        assertEquals("API keys", KnowledgeSearchIntent.topicOrNull("Search in my knowledge base for API keys"))
        assertEquals("OAuth", KnowledgeSearchIntent.topicOrNull("search second brain about OAuth"))
    }

    @Test fun extractsArabicKnowledgeSearch() {
        assertEquals("إعدادات NEXUS", KnowledgeSearchIntent.topicOrNull("ابحث في قاعدة المعرفة عن إعدادات NEXUS"))
        assertEquals("مشاعر", KnowledgeSearchIntent.topicOrNull("أبحث في قاعدة المعرفة عن مشاعر"))
    }

    @Test fun missingTopicNeverUsesModel() {
        assertEquals("", KnowledgeSearchIntent.topicOrNull("Search in my knowledge base for"))
        assertEquals("", KnowledgeSearchIntent.topicOrNull("ابحث في قاعدة المعرفة عن"))
    }

    @Test fun unrelatedMessagesAreNotIntercepted() {
        assertNull(KnowledgeSearchIntent.topicOrNull("What is a knowledge base?"))
        assertNull(KnowledgeSearchIntent.topicOrNull("Send an email about my knowledge base"))
        assertNull(KnowledgeSearchIntent.topicOrNull("Search my Gmail for invoices"))
        assertNull(KnowledgeSearchIntent.topicOrNull("ابحث عن فاتورة في جيميل"))
    }
}
