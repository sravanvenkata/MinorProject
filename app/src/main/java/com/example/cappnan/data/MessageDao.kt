package com.example.cappnan.data


import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Message entities.
 * Defines the methods for reading and writing data to the message_table.
 */
@Dao
interface MessageDao {

    /** Inserts a message into the database. Called for both incoming and outgoing messages. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    /**
     * Loads messages for a specific chat partner in real-time.
     * Returning a Flow ensures that the UI is automatically notified and updated
     * whenever a new message is inserted into the database.
     */
    @Query("SELECT * FROM message_table WHERE partnerName = :partnerName ORDER BY timestamp ASC")
    fun getMessagesForChat(partnerName: String): Flow<List<Message>>

    /** Utility to clear old messages if needed */
    @Query("DELETE FROM message_table")
    suspend fun clearAllMessages()
}