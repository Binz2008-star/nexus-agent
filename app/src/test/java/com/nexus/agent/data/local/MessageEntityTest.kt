package com.nexus.agent.data.local

import com.nexus.agent.domain.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageEntityTest {
    @Test
    fun toDomain_and_fromDomain_roundTrip() {
        val original = ChatMessage(ChatMessage.Role.USER, "hello")
        val entity = MessageEntity.fromDomain(original, conversationId = 1L, timestamp = 1000L)
        val domain = entity.toDomain()
        assertEquals(original.role, domain.role)
        assertEquals(original.text, domain.text)
        assertEquals("USER", entity.role)
        assertEquals(1L, entity.conversationId)
    }

    @Test
    fun assistantRole_mapsCorrectly() {
        val original = ChatMessage(ChatMessage.Role.ASSISTANT, "hi")
        val entity = MessageEntity.fromDomain(original, conversationId = 1L)
        assertEquals("ASSISTANT", entity.role)
        assertEquals(ChatMessage.Role.ASSISTANT, entity.toDomain().role)
    }
}
