package com.nexus.agent.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countMessages(): Int

    @Query("SELECT COUNT(*) FROM messages m LEFT JOIN conversations c ON c.id = m.conversationId WHERE c.id IS NULL")
    suspend fun countOrphanedMessages(): Int

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    fun getByConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    suspend fun getByConversationOnce(conversationId: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: Long)
}
