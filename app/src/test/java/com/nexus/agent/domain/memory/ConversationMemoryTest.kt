package com.nexus.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryTest {

    @Test
    fun dominantEmotionReturnsMostFrequent() {
        val entries = listOf(
            MemoryEntry("أ", emotion = "happy"),
            MemoryEntry("ب", emotion = "happy"),
            MemoryEntry("ج", emotion = "sad")
        )
        assertEquals("happy", ConversationMemory.dominantEmotion(entries))
    }

    @Test
    fun dominantEmotionDefaultsToNeutralWhenNone() {
        val entries = listOf(MemoryEntry("أ"), MemoryEntry("ب"))
        assertEquals("neutral", ConversationMemory.dominantEmotion(entries))
    }

    @Test
    fun formatContextIncludesHistoryAndEmotionInstruction() {
        val entries = listOf(
            MemoryEntry("مرحباً", emotion = "sad"),
            MemoryEntry("أنا حزين اليوم", emotion = "sad")
        )
        val prompt = ConversationMemory.formatContextForAi(entries)
        assertTrue(prompt.contains("سياق المحادثة"))
        assertTrue(prompt.contains("مستخدم: مرحباً"))
        assertTrue(prompt.contains("مستخدم: أنا حزين اليوم"))
        assertTrue(prompt.contains("كن متعاطفًا ولطيفًا")) // sad instruction
    }

    @Test
    fun formatContextReturnsEmptyForNoEntries() {
        assertEquals("", ConversationMemory.formatContextForAi(emptyList()))
    }

    @Test
    fun formatContextCapsEntries() {
        val entries = (1..20).map { MemoryEntry("رسالة $it", emotion = "happy") }
        val prompt = ConversationMemory.formatContextForAi(entries, maxEntries = 3)
        assertTrue(prompt.contains("رسالة 18"))
        assertTrue(prompt.contains("رسالة 20"))
        // 17 is before the last 3 and is not a substring of "رسالة 18".
        assertTrue(!prompt.contains("رسالة 17"))
    }

    @Test
    fun neutralEmotionHasNoInstruction() {
        val entries = listOf(MemoryEntry("مرحباً", emotion = "neutral"))
        val prompt = ConversationMemory.formatContextForAi(entries)
        assertTrue(!prompt.contains("يبدو")) // no emotion instruction for neutral
    }
}
