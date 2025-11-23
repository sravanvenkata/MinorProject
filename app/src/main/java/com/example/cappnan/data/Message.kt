package com.example.cappnan.data


import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.cappnan.ChatMessage

/**
 * Defines the database table structure for storing persistent chat messages.
 * Each instance of this class represents a row in the 'message_table'.
 */
@Entity(tableName = "message_table")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val senderId: String,        // Unique ID or Name of the sender (e.g., "Galaxy S23 (4592)")
    val receiverId: String,      // Unique ID or Name of the receiver (your device)
    val content: String,         // The decrypted message text
    val timestamp: Long,
    val isIncoming: Boolean,     // True if received, False if sent
    val partnerName: String      // The name of the person you are chatting with
) {
    /**
     * Converts the database Message entity into the UI-friendly ChatMessage data class.
     */
    fun toChatMessage(): ChatMessage {
        return ChatMessage(
            text = content,
            isFromMe = !isIncoming, // If NOT incoming, it is from me
            senderName = partnerName,
            timestamp = timestamp
        )
    }
}