package com.example.cappnan

import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.PeerHandle

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