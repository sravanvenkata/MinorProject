package com.example.cappnan

import java.nio.ByteBuffer
import java.nio.charset.Charset

// PACKET TYPES
const val TYPE_RREQ = 1.toByte() // "Where is User X?"
const val TYPE_RREP = 2.toByte() // "I know where User X is!"
const val TYPE_DATA = 3.toByte() // "Hello World"
const val TYPE_ACK  = 4.toByte() // "Message Received"

// A data class to hold the unpacked info
data class AodvPacket(
    val type: Byte,
    val sourceId: Int,
    val destId: Int,
    val packetId: Int,
    val hopCount: Byte,
    val payload: ByteArray
) {
    fun getPayloadString(): String = String(payload, Charset.defaultCharset())
}

object PacketManager {
    private const val HEADER_SIZE = 14

    // Convert Data -> Bytes (Packing)
    fun createPacket(type: Byte, sourceId: Int, destId: Int, packetId: Int, hopCount: Byte, payload: String): ByteArray {
        val payloadBytes = payload.toByteArray()
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payloadBytes.size)

        buffer.put(type)
        buffer.putInt(sourceId)
        buffer.putInt(destId)
        buffer.putInt(packetId)
        buffer.put(hopCount)
        buffer.put(payloadBytes)

        return buffer.array()
    }

    // Convert Bytes -> Data (Unpacking)
    fun parsePacket(bytes: ByteArray): AodvPacket? {
        if (bytes.size < HEADER_SIZE) return null

        val buffer = ByteBuffer.wrap(bytes)
        val type = buffer.get()
        val sourceId = buffer.getInt()
        val destId = buffer.getInt()
        val packetId = buffer.getInt()
        val hopCount = buffer.get()

        val payloadSize = bytes.size - HEADER_SIZE
        val payload = ByteArray(payloadSize)
        buffer.get(payload)

        return AodvPacket(type, sourceId, destId, packetId, hopCount, payload)
    }
}