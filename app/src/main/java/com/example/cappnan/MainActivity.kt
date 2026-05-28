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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.cappnan.ui.ChatScreen
import com.example.cappnan.ui.HomeScreen
import com.example.cappnan.ui.theme.CAppNANTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// --- CONSTANTS ---
private const val AWARE_SERVICE_NAME = "MyAwareService"
private const val TAG = "AwareDebug"

class MainActivity : ComponentActivity() {

    private var wifiAwareManager: WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishSessionRef: PublishDiscoverySession? = null
    private var subscribeSessionRef: SubscribeDiscoverySession? = null

    // IDENTITY
    private var myId: String = ""
    private var myNodeId: Int = 0
    private var myName: String = ""
    private lateinit var myPrivateKey: java.security.PrivateKey

    // --- DATABASE ---
    private lateinit var db: AppDatabase

    // UI & CONNECTION STATE
    private val activeConnections = mutableStateMapOf<Int, PeerConnection>() // Maps NodeID -> Active Wi-Fi Connection
    private var currentChatTarget: String? = null

    // AODV STATE
    private val routingTable = mutableMapOf<Int, RouteEntry>()
    private val seenPackets = mutableSetOf<String>()
    private val messageBuffer = mutableMapOf<Int, MutableList<String>>()
    private var packetSequenceNumber = 0

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.all { p -> p.value }) attachToWifiAware() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. INIT DATABASE
        db = AppDatabase.getDatabase(this)

        // 2. GENERATE ID & CRYPTO KEYS ON FIRST LAUNCH
        val prefs: SharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        var storedId = prefs.getString("MY_ID", null)
        var storedPubKey = prefs.getString("MY_PUB_KEY", null)
        var storedPrivKey = prefs.getString("MY_PRIV_KEY", null)

        if (storedId == null || storedPubKey == null || storedPrivKey == null) {
            storedId = kotlin.random.Random.nextInt(1000, 9999).toString()
            val keyPair = CryptoManager.generateECCKeyPair()
            storedPubKey = CryptoManager.encodeKeyToBase64(keyPair.public)
            storedPrivKey = CryptoManager.encodeKeyToBase64(keyPair.private)

            prefs.edit()
                .putString("MY_ID", storedId)
                .putString("MY_PUB_KEY", storedPubKey)
                .putString("MY_PRIV_KEY", storedPrivKey)
                .apply()
        }

        myId = storedId
        myNodeId = myId.toInt()
        myPrivateKey = CryptoManager.decodeBase64ToPrivateKey(storedPrivKey!!)

        // NOTE: We MUST keep the ID in this string. Wi-Fi Aware uses this broadcast to know who is who.
        myName = "${Build.MODEL} ($myId)"
        val myPublicKeyBase64 = storedPubKey

        wifiAwareManager = getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager

        setContent {
            CAppNANTheme {
                val navController = rememberNavController()
                val dbFriends by db.friendDao().getAllFriends().collectAsState(initial = emptyList())

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        val friendNames = dbFriends.map { it.name }
                        HomeScreen(
                            myId = myId,
                            myName = myName,
                            myPublicKey = myPublicKeyBase64,
                            friends = friendNames,
                            onChatClick = { name ->
                                currentChatTarget = name
                                navController.navigate("chat")
                            },
                            onAddFriendClick = { navController.navigate("scan_qr") }
                        )
                    }
                    composable("scan_qr") {
                        com.example.cappnan.ui.ScanQrScreen(
                            onQrScanned = { qrData ->
                                val parts = qrData.split(":")
                                if (parts.size == 4) {
                                    val newFriendId = parts[1].toInt()
                                    val newFriendName = parts[2]
                                    val newFriendPubKey = parts[3]

                                    lifecycleScope.launch(Dispatchers.IO) {
                                        db.friendDao().insertFriend(FriendEntity(nodeId = newFriendId, name = newFriendName, publicKey = newFriendPubKey))
                                    }
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, "Added $newFriendName!", Toast.LENGTH_SHORT).show()
                                        navController.popBackStack()
                                    }
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("chat") {
                        val targetName = currentChatTarget ?: "Unknown"
                        val targetId = extractIdFromName(targetName)

                        val dbMessages by if (targetId != 0) {
                            db.messageDao().getMessagesForFriend(targetId).collectAsState(initial = emptyList())
                        } else {
                            remember { mutableStateOf(emptyList()) }
                        }

                        val msgs = dbMessages.map { ChatMessage(it.text, it.isFromMe, targetName, it.timestamp) }

                        ChatScreen(
                            peerName = targetName,
                            messages = msgs,
                            onSendMessage = { msg -> sendMessage(msg) },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
        if (wifiAwareManager != null) requestPermissions()
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)
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
                val peerId = extractIdFromName(peerName)
                if (peerId != 0) activeConnections[peerId] = PeerConnection(peerHandle, subscribeSessionRef!!)
            }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) { handleIncomingMessage(peerHandle, message, subscribeSessionRef!!) }
        }, null)
    }

    private fun handleIncomingMessage(handle: PeerHandle, message: ByteArray, session: DiscoverySession) {
        val packet = PacketManager.parsePacket(message)
        if (packet != null) handleAodvPacket(packet, handle, session)
    }

    private fun handleAodvPacket(packet: AodvPacket, prevHandle: PeerHandle, prevSession: DiscoverySession) {
        val packetKey = "${packet.sourceId}-${packet.packetId}"
        if (seenPackets.contains(packetKey)) return
        seenPackets.add(packetKey)

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
                    // X-RAY VISION ADDED BACK
                    runOnUiThread { Toast.makeText(this@MainActivity, "Encrypted packet arrived!", Toast.LENGTH_SHORT).show() }

                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val friend = db.friendDao().getFriendById(packet.sourceId)
                            if (friend == null || friend.publicKey == "pending_key") {
                                runOnUiThread { Toast.makeText(this@MainActivity, "Decryption Failed: Missing QR Key!", Toast.LENGTH_LONG).show() }
                                return@launch
                            }

                            val theirPublicKey = CryptoManager.decodeBase64ToPublicKey(friend.publicKey)
                            val sharedSecret = CryptoManager.generateSharedSecret(myPrivateKey, theirPublicKey)

                            val encryptedBytes = android.util.Base64.decode(packet.getPayloadString(), android.util.Base64.NO_WRAP)
                            val decryptedText = CryptoManager.decryptMessage(encryptedBytes, sharedSecret)

                            db.messageDao().insertMessage(MessageEntity(chatPartnerId = packet.sourceId, text = decryptedText, isFromMe = false))

                            // X-RAY VISION ADDED BACK
                            runOnUiThread { Toast.makeText(this@MainActivity, "Message Unlocked!", Toast.LENGTH_SHORT).show() }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decrypt incoming message", e)
                        }
                    }
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
        activeConnections.values.forEach { if(it.handle != excludeHandle) try { it.session.sendMessage(it.handle, 0, bytes) } catch(e:Exception){} }
    }
    private fun forwardPacketToNextHop(packet: AodvPacket) {
        val nextHop = routingTable[packet.destId] ?: return
        val newHops = (packet.hopCount + 1).toByte()
        val bytes = PacketManager.createPacket(packet.type, packet.sourceId, packet.destId, packet.packetId, newHops, packet.getPayloadString())
        try { nextHop.nextHopSession.sendMessage(nextHop.nextHopHandle, 0, bytes) } catch(e:Exception){}
    }

    private fun sendMessage(text: String) {
        val targetName = currentChatTarget ?: return
        val targetId = extractIdFromName(targetName)

        if (targetId != 0) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.messageDao().insertMessage(MessageEntity(chatPartnerId = targetId, text = text, isFromMe = true))
            }
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
        // X-RAY VISION ADDED BACK
        if (activeConnections.isEmpty()) {
            runOnUiThread { Toast.makeText(this, "Waiting for Wi-Fi Mesh to sync...", Toast.LENGTH_LONG).show() }
            return
        }

        packetSequenceNumber++
        val bytes = PacketManager.createPacket(TYPE_RREQ, myNodeId, targetId, packetSequenceNumber, 0, "")
        activeConnections.values.forEach { try { it.session.sendMessage(it.handle, 0, bytes) } catch (e: Exception) {} }
        runOnUiThread { Toast.makeText(this, "Searching for route...", Toast.LENGTH_SHORT).show() }
    }

    private fun sendDataPacket(handle: PeerHandle, session: DiscoverySession, src: Int, dst: Int, text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val friend = db.friendDao().getFriendById(dst)
                if (friend == null || friend.publicKey == "pending_key") return@launch

                val theirPublicKey = CryptoManager.decodeBase64ToPublicKey(friend.publicKey)
                val sharedSecret = CryptoManager.generateSharedSecret(myPrivateKey, theirPublicKey)
                val encryptedBytes = CryptoManager.encryptMessage(text, sharedSecret)

                val encryptedString = android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)
                packetSequenceNumber++
                val bytes = PacketManager.createPacket(TYPE_DATA, src, dst, packetSequenceNumber, 0, encryptedString)

                session.sendMessage(handle, 0, bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Encryption or Sending failed", e)
            }
        }
    }

    private fun flushMessageBuffer(targetId: Int) {
        val queue = messageBuffer[targetId] ?: return
        val route = routingTable[targetId] ?: return
        queue.forEach { sendDataPacket(route.nextHopHandle, route.nextHopSession, myNodeId, targetId, it) }
        messageBuffer.remove(targetId)
    }

    private fun extractIdFromName(name: String): Int {
        val idString = name.substringAfterLast("(", "").substringBefore(")", "")
        return idString.toIntOrNull() ?: 0
    }

    override fun onDestroy() { super.onDestroy(); wifiAwareSession?.close() }
}