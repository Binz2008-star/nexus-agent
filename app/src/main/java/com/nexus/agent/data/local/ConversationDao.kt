package com.nexus.agent.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET updatedAt = :ts, title = :title WHERE id = :id")
    suspend fun updateTitleAndTimestamp(id: Long, title: String, ts: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
}
