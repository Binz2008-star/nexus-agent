package com.nexus.agent.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nexus.agent.domain.model.ChatMessage

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val role: String, // "USER" or "ASSISTANT"
    val text: String
) {
    fun toDomain(): ChatMessage = ChatMessage(
        role = when (role) {
            "USER" -> ChatMessage.Role.USER
            "ASSISTANT" -> ChatMessage.Role.ASSISTANT
            "SYSTEM" -> ChatMessage.Role.SYSTEM
            else -> throw IllegalArgumentException("Unknown role: $role")
        },
        text = text,
        id = id
    )

    companion object {
        fun fromDomain(message: ChatMessage, timestamp: Long = System.currentTimeMillis()): MessageEntity =
            MessageEntity(
                timestamp = timestamp,
                role = message.role.name,
                text = message.text
            )
    }
}
