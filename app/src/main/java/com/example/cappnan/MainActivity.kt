package com.example.cappnan

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.aware.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.cappnan.ui.AddFriendScreen
import com.example.cappnan.ui.ChatScreen
import com.example.cappnan.ui.HomeScreen
import com.example.cappnan.ui.theme.CAppNANTheme
import kotlin.random.Random

// --- CONSTANTS ---
private const val AWARE_SERVICE_NAME = "MyAwareService"
private const val TAG = "AwareDebug"

// LEGACY PROTOCOLS (For Friend Requests)
private const val REQ_PREFIX = "REQ:"
private const val ACK_PREFIX = "ACK:"



class MainActivity : ComponentActivity() {

    private var wifiAwareManager: WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishSessionRef: PublishDiscoverySession? = null
    private var subscribeSessionRef: SubscribeDiscoverySession? = null

    // IDENTITY
    private var myId: String = "" // "4592"
    private var myNodeId: Int = 0 // 4592 (Int version for AODV)
    private var myName: String = ""

    // UI STATE
    private val discoveredStrangers = mutableStateMapOf<String, PeerConnection>()
    private val friendsList = mutableStateMapOf<String, PeerConnection>()
    private val allMessages = mutableStateListOf<ChatMessage>()

    // FIX 2: Variable was missing in your snippets
    private var currentChatTarget: String? = null

    // AODV STATE
    private val routingTable = mutableMapOf<Int, RouteEntry>()
    private val seenPackets = mutableSetOf<String>()
    private val messageBuffer = mutableMapOf<Int, MutableList<String>>()
    private var packetSequenceNumber = 0

    // POPUP STATE
    private var showConnectionRequest by mutableStateOf(false)
    private var requestSenderName by mutableStateOf("")
    private var requestSenderHandle: PeerHandle? = null
    private var requestSession: DiscoverySession? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.all { p -> p.value }) attachToWifiAware() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. GENERATE ID
        val prefs: SharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        var storedId = prefs.getString("MY_ID", null)
        if (storedId == null) {
            storedId = Random.nextInt(1000, 9999).toString()
            prefs.edit().putString("MY_ID", storedId).apply()
        }
        myId = storedId!!
        myNodeId = myId.toInt()
        myName = "${Build.MODEL} ($myId)"

        wifiAwareManager = getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager

        setContent {
            CAppNANTheme {
                val navController = rememberNavController()

                if (showConnectionRequest) {
                    AlertDialog(
                        onDismissRequest = { showConnectionRequest = false },
                        title = { Text("Friend Request") },
                        text = { Text("$requestSenderName wants to connect.") },
                        confirmButton = { Button(onClick = { acceptConnection(); showConnectionRequest = false }) { Text("Allow") } },
                        dismissButton = { Button(onClick = { showConnectionRequest = false }) { Text("Deny") } }
                    )
                }

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            myId = myId,
                            friends = friendsList.keys.toList(),
                            onChatClick = { name ->
                                currentChatTarget = name
                                navController.navigate("chat")
                            },
                            onAddFriendClick = { navController.navigate("add_friend") }
                        )
                    }
                    composable("add_friend") {
                        val strangers = discoveredStrangers.keys.filter { !friendsList.containsKey(it) }
                        AddFriendScreen(
                            discoveredDevices = strangers,
                            onConnectClick = { name -> sendConnectionRequest(name) },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("chat") {
                        val target = currentChatTarget ?: "Unknown"
                        val msgs = allMessages.filter { it.senderName == target || (it.isFromMe && it.senderName == target) }
                        ChatScreen(
                            peerName = target,
                            messages = msgs,
                            onSendMessage = { msg -> sendMessage(msg) }, // Calls the UI-facing sendMessage
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
        if (wifiAwareManager != null) requestPermissions()
    }

    // --- SETUP ---
    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) attachToWifiAware()
        else requestPermissionLauncher.launch(perms.toTypedArray())
    }
    private fun attachToWifiAware() { if (wifiAwareManager?.isAvailable == true) attach() }
    private fun attach() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        wifiAwareManager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                wifiAwareSession = session
                publish()
                subscribe()
                Toast.makeText(this@MainActivity, "Active: $myNodeId", Toast.LENGTH_SHORT).show()
            }
        }, null)
    }
    private fun publish() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val config = PublishConfig.Builder().setServiceName(AWARE_SERVICE_NAME).setServiceSpecificInfo(myName.toByteArray()).build()
        wifiAwareSession?.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) { publishSessionRef = session }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) { handleIncomingMessage(peerHandle, message, publishSessionRef!!) }
        }, null)
    }
    private fun subscribe() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val config = SubscribeConfig.Builder().setServiceName(AWARE_SERVICE_NAME).build()
        wifiAwareSession?.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) { subscribeSessionRef = session }
            override fun onServiceDiscovered(peerHandle: PeerHandle, info: ByteArray, filter: List<ByteArray>) {
                val peerName = String(info)
                discoveredStrangers[peerName] = PeerConnection(peerHandle, subscribeSessionRef!!)
            }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) { handleIncomingMessage(peerHandle, message, subscribeSessionRef!!) }
        }, null)
    }

    // --- INCOMING HANDLER (SWITCHBOARD) ---
    // --- INCOMING HANDLER (SWITCHBOARD) ---
    private fun handleIncomingMessage(handle: PeerHandle, message: ByteArray, session: DiscoverySession) {
        val text = String(message)

        // FIX: Check for Text Prefixes FIRST.
        // If it starts with "REQ:" or "ACK:", handle it as a Friend Request immediately.
        if (text.startsWith(REQ_PREFIX) || text.startsWith(ACK_PREFIX)) {
            handleLegacyStringMessage(text, handle, session)
            return
        }

        // If it wasn't a text command, TRY to parse as Binary AODV Packet
        val packet = PacketManager.parsePacket(message)

        if (packet != null) {
            handleAodvPacket(packet, handle, session)
        }
    }

    // --- LEGACY FRIEND LOGIC ---
    private fun handleLegacyStringMessage(text: String, handle: PeerHandle, session: DiscoverySession) {
        if (text.startsWith(REQ_PREFIX)) {
            val sender = text.removePrefix(REQ_PREFIX)
            discoveredStrangers[sender] = PeerConnection(handle, session)
            requestSenderName = sender; requestSenderHandle = handle; requestSession = session
            showConnectionRequest = true
        } else if (text.startsWith(ACK_PREFIX)) {
            val sender = text.removePrefix(ACK_PREFIX)
            // Save as Friend AND Routing Neighbor
            friendsList[sender] = PeerConnection(handle, session)
            // Also update Routing Table (Direct connection = 1 hop)
            try {
                val idStr = sender.substringAfterLast("(").substringBefore(")")
                routingTable[idStr.toInt()] = RouteEntry(handle, session, 1)
            } catch(e: Exception){}
            runOnUiThread { Toast.makeText(this, "Friend Added!", Toast.LENGTH_SHORT).show() }
        }
    }
    private fun sendConnectionRequest(targetName: String) {
        val conn = discoveredStrangers[targetName] ?: return
        try { conn.session.sendMessage(conn.handle, 0, "$REQ_PREFIX$myName".toByteArray()) } catch (e: Exception) {}
    }
    private fun acceptConnection() {
        val handle = requestSenderHandle ?: return
        val session = requestSession ?: return
        friendsList[requestSenderName] = PeerConnection(handle, session)
        // Update routing table for new friend
        try {
            val idStr = requestSenderName.substringAfterLast("(").substringBefore(")")
            routingTable[idStr.toInt()] = RouteEntry(handle, session, 1)
        } catch(e:Exception){}
        try { session.sendMessage(handle, 0, "$ACK_PREFIX$myName".toByteArray()) } catch (e: Exception) {}
    }

    // --- AODV ROUTING LOGIC ---
    private fun handleAodvPacket(packet: AodvPacket, prevHandle: PeerHandle, prevSession: DiscoverySession) {
        val packetKey = "${packet.sourceId}-${packet.packetId}"
        if (seenPackets.contains(packetKey)) return
        seenPackets.add(packetKey)

        // Update Reverse Route
        routingTable[packet.sourceId] = RouteEntry(prevHandle, prevSession, packet.hopCount + 1)

        when (packet.type) {
            TYPE_RREQ -> {
                if (packet.destId == myNodeId) sendRREP(packet.sourceId, prevHandle, prevSession)
                else relayPacket(packet, prevHandle)
            }
            TYPE_RREP -> {
                if (packet.destId == myNodeId) flushMessageBuffer(packet.sourceId)
                else forwardPacketToNextHop(packet)
            }
            TYPE_DATA -> {
                if (packet.destId == myNodeId) {
                    val senderName = friendsList.keys.find { it.contains(packet.sourceId.toString()) } ?: "User ${packet.sourceId}"
                    runOnUiThread { allMessages.add(ChatMessage(packet.getPayloadString(), false, senderName)) }
                } else {
                    forwardPacketToNextHop(packet)
                }
            }
        }
    }

    private fun sendRREP(originId: Int, handle: PeerHandle, session: DiscoverySession) {
        packetSequenceNumber++
        val bytes = PacketManager.createPacket(TYPE_RREP, myNodeId, originId, packetSequenceNumber, 0, "")
        try { session.sendMessage(handle, 0, bytes) } catch (e: Exception) {}
    }

    private fun relayPacket(packet: AodvPacket, excludeHandle: PeerHandle) {
        val newHops = (packet.hopCount + 1).toByte()
        val bytes = PacketManager.createPacket(packet.type, packet.sourceId, packet.destId, packet.packetId, newHops, "")
        friendsList.values.forEach { if(it.handle != excludeHandle) try { it.session.sendMessage(it.handle, 0, bytes) } catch(e:Exception){} }
    }

    private fun forwardPacketToNextHop(packet: AodvPacket) {
        val nextHop = routingTable[packet.destId] ?: return
        val newHops = (packet.hopCount + 1).toByte()
        val bytes = PacketManager.createPacket(packet.type, packet.sourceId, packet.destId, packet.packetId, newHops, packet.getPayloadString())
        try { nextHop.nextHopSession.sendMessage(nextHop.nextHopHandle, 0, bytes) } catch(e:Exception){}
    }

    // --- UI SEND ACTION ---
    // FIX 1: This is the missing function!
    // REPLACE your sendMessage function with this (Uncommented):
    // REPLACE your sendMessage function with this (Uncommented):
    private fun sendMessage(text: String) {
        val targetName = currentChatTarget ?: return

        // Logic: Extract ID from "Samsung (4592)" -> 4592
        val idString = targetName.substringAfterLast("(").substringBefore(")")
        val targetId = idString.toIntOrNull()

        if (targetId != null) {
            // This triggers the AODV routing
            sendRoutedMessage(text, targetId)
        } else {
            Toast.makeText(this, "Invalid Target ID", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendRoutedMessage(text: String, targetId: Int) {
        val route = routingTable[targetId]
        if (route != null) {
            sendDataPacket(route.nextHopHandle, route.nextHopSession, myNodeId, targetId, text)
        } else {
            if (!messageBuffer.containsKey(targetId)) messageBuffer[targetId] = mutableListOf()
            messageBuffer[targetId]?.add(text)
            broadcastRREQ(targetId)
        }
    }

    private fun broadcastRREQ(targetId: Int) {
        packetSequenceNumber++
        val bytes = PacketManager.createPacket(TYPE_RREQ, myNodeId, targetId, packetSequenceNumber, 0, "")
        friendsList.values.forEach { try { it.session.sendMessage(it.handle, 0, bytes) } catch (e: Exception) {} }
    }

    private fun sendDataPacket(handle: PeerHandle, session: DiscoverySession, src: Int, dst: Int, text: String) {
        packetSequenceNumber++
        val bytes = PacketManager.createPacket(TYPE_DATA, src, dst, packetSequenceNumber, 0, text)
        try {
            session.sendMessage(handle, 0, bytes)
            // UI Update for Self
            if (src == myNodeId) {
                val name = friendsList.keys.find { it.contains(dst.toString()) } ?: dst.toString()
                runOnUiThread { allMessages.add(ChatMessage(text, true, name)) }
            }
        } catch(e:Exception){}
    }

    private fun flushMessageBuffer(targetId: Int) {
        val queue = messageBuffer[targetId] ?: return
        val route = routingTable[targetId] ?: return
        queue.forEach { sendDataPacket(route.nextHopHandle, route.nextHopSession, myNodeId, targetId, it) }
        messageBuffer.remove(targetId)
    }

    override fun onDestroy() { super.onDestroy(); wifiAwareSession?.close() }
}