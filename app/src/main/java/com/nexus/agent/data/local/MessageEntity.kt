package com.nexus.agent.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nexus.agent.domain.model.ChatMessage

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val conversationId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val role: String,
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
        fun fromDomain(message: ChatMessage, conversationId: Long, timestamp: Long = System.currentTimeMillis()): MessageEntity =
            MessageEntity(
                conversationId = conversationId,
                timestamp = timestamp,
                role = message.role.name,
                text = message.text
            )
    }
}
