package com.nexus.agent.domain.commands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingDetectorTest {

    @Test
    fun detectsEnglishGreetings() {
        assertTrue(GreetingDetector.isConversational("hi"))
        assertTrue(GreetingDetector.isConversational("hello"))
        assertTrue(GreetingDetector.isConversational("hey"))
        assertTrue(GreetingDetector.isConversational("hi there"))
    }

    @Test
    fun detectsArabicGreetings() {
        assertTrue(GreetingDetector.isConversational("مرحبا"))
        assertTrue(GreetingDetector.isConversational("أهلاً"))
        assertTrue(GreetingDetector.isConversational("السلام عليكم"))
    }

    @Test
    fun detectsSmallTalk() {
        assertTrue(GreetingDetector.isConversational("كيف حالك؟"))
        assertTrue(GreetingDetector.isConversational("how are you"))
        assertTrue(GreetingDetector.isConversational("what's up"))
    }

    @Test
    fun ignoresExplanationRequests() {
        assertFalse(GreetingDetector.isConversational("Explain what HI means"))
        assertFalse(GreetingDetector.isConversational("what does hi mean"))
        assertFalse(GreetingDetector.isConversational("وش يعني مرحبا"))
    }

    @Test
    fun ignoresNormalQuestions() {
        assertFalse(GreetingDetector.isConversational("what's the weather today"))
        assertFalse(GreetingDetector.isConversational("ما هو مشروع rico"))
        assertFalse(GreetingDetector.isConversational(""))
    }
}
