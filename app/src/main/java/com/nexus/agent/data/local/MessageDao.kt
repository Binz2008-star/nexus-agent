package com.nexus.agent.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages ORDER BY timestamp ASC, id ASC")
    fun getAllChronological(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC, id ASC")
    suspend fun getAllChronologicalOnce(): List<MessageEntity>
}
