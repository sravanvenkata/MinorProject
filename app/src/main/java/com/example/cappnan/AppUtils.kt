package com.example.cappnan

import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.PeerHandle
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

// --- SHARED DATA CLASSES ---

// 1. For Chat Messages
data class ChatMessage(
    val text: String,
    val isFromMe: Boolean,
    val senderName: String,
    val timestamp: Long = System.currentTimeMillis()
)

// 2. For Connections (Friend List)
data class PeerConnection(
    val handle: PeerHandle,
    val session: DiscoverySession
)

// 3. For Routing (AODV)
data class RouteEntry(
    val nextHopHandle: PeerHandle,
    val nextHopSession: DiscoverySession,
    val hopCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)
fun generateQrCodeBitmap(content: String, size: Int = 512): Bitmap? {
    return try {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}