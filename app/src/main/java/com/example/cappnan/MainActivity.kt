package com.example.cappnan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.cappnan.ui.ChatScreen
import com.example.cappnan.ui.DeviceListScreen
import com.example.cappnan.ui.theme.CAppNANTheme
import java.nio.charset.Charset

// Constants
private const val AWARE_SERVICE_NAME = "MyAwareService"
private const val TAG = "AwareDebug"
private const val MSG_PREFIX = "MSG:"
private const val HANDSHAKE_PREFIX = "HANDSHAKE:"

// DATA CLASSES
data class ChatMessage(
    val text: String,
    val isFromMe: Boolean,
    val senderName: String,
    val timestamp: Long = System.currentTimeMillis()
)

// FIX: This class remembers WHICH session allows us to talk to this person
data class PeerConnection(
    val handle: PeerHandle,
    val session: DiscoverySession // Can be PublishDiscoverySession OR SubscribeDiscoverySession
)

class MainActivity : ComponentActivity() {

    private var wifiAwareManager: WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null

    // We keep specific references just to keep sessions alive
    private var publishSessionRef: PublishDiscoverySession? = null
    private var subscribeSessionRef: SubscribeDiscoverySession? = null

    // FIX: Map Name -> The Exact Connection Info (Handle + Session)
    private val connectedPeers = mutableStateMapOf<String, PeerConnection>()

    private val allMessages = mutableStateListOf<ChatMessage>()
    private var currentChatTarget: String? = null

    // Message ID counter to prevent de-duplication drops
    private var messageIdCounter = 100

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) attachToWifiAware()
            else Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiAwareManager = getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager

        setContent {
            CAppNANTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "device_list") {

                    composable("device_list") {
                        DeviceListScreen(
                            deviceNames = connectedPeers.keys.toList(),
                            onDeviceClick = { name ->
                                currentChatTarget = name
                                navController.navigate("chat")
                            }
                        )
                    }

                    composable("chat") {
                        val target = currentChatTarget ?: "Unknown"
                        val filteredMessages = allMessages.filter {
                            it.senderName == target || (it.isFromMe && it.senderName == target)
                        }.sortedBy { it.timestamp }

                        ChatScreen(
                            peerName = target,
                            messages = filteredMessages,
                            onSendMessage = { msg -> sendMessage(msg) },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }

        if (wifiAwareManager != null) requestPermissions()
        else Toast.makeText(this, "Hardware not supported", Toast.LENGTH_LONG).show()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            attachToWifiAware()
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun attachToWifiAware() {
        if (wifiAwareManager?.isAvailable == true) attach()
    }

    private fun attach() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        wifiAwareManager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                wifiAwareSession = session
                publish()
                subscribe()
                Toast.makeText(applicationContext, "Session Active: ${Build.MODEL}", Toast.LENGTH_SHORT).show()
            }
        }, null)
    }

    private fun publish() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val config = PublishConfig.Builder()
            .setServiceName(AWARE_SERVICE_NAME)
            .setServiceSpecificInfo(Build.MODEL.toByteArray())
            .build()

        wifiAwareSession?.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                publishSessionRef = session
            }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                // IMPORTANT: Pass the session that received this message
                handleIncomingMessage(peerHandle, message, session = publishSessionRef!!)
            }
        }, null)
    }

    private fun subscribe() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val config = SubscribeConfig.Builder().setServiceName(AWARE_SERVICE_NAME).build()

        wifiAwareSession?.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                subscribeSessionRef = session
            }

            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray, matchFilter: List<ByteArray>) {
                val peerName = String(serviceSpecificInfo)

                // Save connection using THIS Subscribe Session
                connectedPeers[peerName] = PeerConnection(peerHandle, subscribeSessionRef!!)

                // Send Handshake
                val myHandshake = "$HANDSHAKE_PREFIX${Build.MODEL}"
                try {
                    subscribeSessionRef?.sendMessage(peerHandle, messageIdCounter++, myHandshake.toByteArray())
                } catch (e: Exception) { Log.e(TAG, "Handshake failed", e) }
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                // IMPORTANT: Pass the session that received this message
                handleIncomingMessage(peerHandle, message, session = subscribeSessionRef!!)
            }
        }, null)
    }

    // --- LOGIC: STRICT SESSION TRACKING ---
    private fun handleIncomingMessage(peerHandle: PeerHandle, message: ByteArray, session: DiscoverySession) {
        val rawText = String(message)

        if (rawText.startsWith(HANDSHAKE_PREFIX)) {
            val peerName = rawText.removePrefix(HANDSHAKE_PREFIX)
            // UPDATE: We now know exactly which session to use to reply to this person
            connectedPeers[peerName] = PeerConnection(peerHandle, session)
            Log.d(TAG, "Handshake from $peerName on ${session.javaClass.simpleName}")
        }
        else if (rawText.startsWith(MSG_PREFIX)) {
            try {
                val parts = rawText.split(":", limit = 3)
                if (parts.size == 3) {
                    val senderName = parts[1]
                    val actualMsg = parts[2]

                    // UPDATE: Ensure we map this sender to this session
                    connectedPeers[senderName] = PeerConnection(peerHandle, session)

                    runOnUiThread {
                        allMessages.add(ChatMessage(actualMsg, isFromMe = false, senderName = senderName))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message", e)
            }
        }
    }

    // --- LOGIC: NO MORE GUESSING ---
    private fun sendMessage(text: String) {
        val targetName = currentChatTarget ?: return

        // 1. Get the EXACT connection object (Handle + Session)
        val connection = connectedPeers[targetName]

        if (connection == null) {
            Toast.makeText(this, "Lost connection to $targetName", Toast.LENGTH_SHORT).show()
            return
        }

        val payload = "$MSG_PREFIX${Build.MODEL}:$text"
        val bytes = payload.toByteArray()

        try {
            // 2. Use the stored session directly. No try/catch spraying.
            connection.session.sendMessage(connection.handle, messageIdCounter++, bytes)

            runOnUiThread {
                allMessages.add(ChatMessage(text, isFromMe = true, senderName = targetName))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            Toast.makeText(this, "Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiAwareSession?.close()
    }
}