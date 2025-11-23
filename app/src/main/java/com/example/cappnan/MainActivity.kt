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
import com.example.cappnan.data.AppDatabase
import com.example.cappnan.data.Message
import com.example.cappnan.data.MessageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map // Essential for converting DB Entity to UI Model


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

    // --- DATABASE STATE ---
    private lateinit var database: AppDatabase
    private lateinit var messageDao: MessageDao
    private val coroutineScope = CoroutineScope(Dispatchers.IO) // For database operations

    // IDENTITY
    private var myId: String = "" // "4592"
    private var myNodeId: Int = 0 // 4592 (Int version for AODV)
    private var myName: String = ""

    // UI STATE
    private val discoveredStrangers = mutableStateMapOf<String, PeerConnection>()
    private val friendsList = mutableStateMapOf<String, PeerConnection>()

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

        // 2. Initialize Database and DAO (Essential for persistence)
        database = AppDatabase.getDatabase(this)
        messageDao = database.messageDao()

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

                        // --- CORRECT PERSISTENCE: FLOW OBSERVATION ---
                        // 1. Get the Flow from the DAO (This watches the database in real-time)
                        val messageFlow = remember(target) {
                            messageDao.getMessagesForChat(target).map { dbList ->
                                dbList.map { it.toChatMessage() } // Convert DB Entities to UI ChatMessages
                            }
                        }
                        // 2. Collect the Flow as a Compose State
                        val msgs by messageFlow.collectAsState(initial = emptyList())
                        // --- END PERSISTENCE FIX ---

                        ChatScreen(
                            peerName = target,
                            messages = msgs, // This list is now the persistent, real-time data
                            onSendMessage = { msg -> sendMessage(msg) },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
        if (wifiAwareManager != null) requestPermissions()
    }

    // --- SETUP (No changes) ---
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
    private fun handleIncomingMessage(handle: PeerHandle, message: ByteArray, session: DiscoverySession) {
        val text = String(message)

        if (text.startsWith(REQ_PREFIX) || text.startsWith(ACK_PREFIX)) {
            handleLegacyStringMessage(text, handle, session)
            return
        }

        val packet = PacketManager.parsePacket(message)

        if (packet != null) {
            handleAodvPacket(packet, handle, session)
        }
    }

    // --- LEGACY FRIEND LOGIC (No changes) ---
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
                    val content = packet.getPayloadString()

                    // --- SAVE INCOMING MESSAGE TO DB (Persistence) ---
                    val messageEntity = Message(
                        senderId = senderName,
                        receiverId = myName, // Your own device name/ID
                        content = content,
                        timestamp = System.currentTimeMillis(),
                        isIncoming = true,
                        partnerName = senderName
                    )
                    coroutineScope.launch {
                        messageDao.insertMessage(messageEntity)
                    }
                    // --- END DB SAVE ---

                } else {
                    forwardPacketToNextHop(packet)
                }
            }
        }
    }

    private fun sendRREP(originId: Int, handle: PeerHandle, session: DiscoverySession) {
        packetSequenceNumber++
        val bytes = PacketManager.createPacket(TYPE_RREP, myNodeId, originId, packetSequenceNumber, 0.toByte(), "")
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

    private fun flushMessageBuffer(targetId: Int) {
        val queue = messageBuffer[targetId] ?: return
        val route = routingTable[targetId] ?: return
        queue.forEach {
            // Send each message in the buffer as a data packet
            sendDataPacket(route.nextHopHandle, route.nextHopSession, myNodeId, targetId, it)
        }
        messageBuffer.remove(targetId)
    }

    // --- UI SEND ACTION ---
    private fun sendMessage(text: String) {
        val targetName = currentChatTarget ?: return
        val targetId = try {
            targetName.substringAfterLast("(").substringBefore(")").toInt()
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid Target ID", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Save outgoing message to DB immediately (Persistence)
        coroutineScope.launch {
            messageDao.insertMessage(
                Message(
                    senderId = myName,
                    receiverId = targetName,
                    content = text,
                    timestamp = System.currentTimeMillis(),
                    isIncoming = false,
                    partnerName = targetName
                )
            )
        }

        // 2. Network Logic (AODV)
        val route = routingTable[targetId]
        if (route != null) {
            // We have a route! Send DATA packet immediately using the next hop.
            sendDataPacket(route.nextHopHandle, route.nextHopSession, myNodeId, targetId, text)
        } else {
            // No route? Buffer message and Flood RREQ
            if (!messageBuffer.containsKey(targetId)) messageBuffer[targetId] = mutableListOf()
            messageBuffer[targetId]?.add(text)
            broadcastRREQ(targetId)
        }
    }

    // --- RESTORED MISSING FUNCTIONS (Essential for AODV) ---
    private fun broadcastRREQ(targetId: Int) {
        packetSequenceNumber++
        val bytes = PacketManager.createPacket(TYPE_RREQ, myNodeId, targetId, packetSequenceNumber, 0.toByte(), "")
        friendsList.values.forEach { try { it.session.sendMessage(it.handle, 0, bytes) } catch (e: Exception) {} }
    }

    private fun sendDataPacket(handle: PeerHandle, session: DiscoverySession, src: Int, dst: Int, text: String) {
        packetSequenceNumber++
        val bytes = PacketManager.createPacket(TYPE_DATA, src, dst, packetSequenceNumber, 0.toByte(), text)
        try {
            session.sendMessage(handle, 0, bytes)
        } catch(e:Exception){
            Log.e(TAG, "Failed to send data packet: ${e.message}")
            runOnUiThread { Toast.makeText(this, "Failed to send to next hop.", Toast.LENGTH_SHORT).show() }
        }
    }
    // --- END RESTORED FUNCTIONS ---


    override fun onDestroy() { super.onDestroy(); wifiAwareSession?.close() }
}