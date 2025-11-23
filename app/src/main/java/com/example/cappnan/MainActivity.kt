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

// PROTOCOLS
private const val REQ_PREFIX = "REQ:"   // "REQ:1234:Samsung"
private const val ACK_PREFIX = "ACK:"   // "ACK:5678:Pixel"
private const val MSG_PREFIX = "MSG:"   // "MSG:1234:Hello"

data class ChatMessage(val text: String, val isFromMe: Boolean, val senderName: String, val timestamp: Long = System.currentTimeMillis())
data class PeerConnection(val handle: PeerHandle, val session: DiscoverySession)

class MainActivity : ComponentActivity() {

    private var wifiAwareManager: WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishSessionRef: PublishDiscoverySession? = null
    private var subscribeSessionRef: SubscribeDiscoverySession? = null

    // MY IDENTITY
    private var myId: String = ""
    private var myName: String = ""

    // STATE
    private val discoveredStrangers = mutableStateMapOf<String, PeerConnection>() // Temporary (Add Friend Screen)
    private val friendsList = mutableStateMapOf<String, PeerConnection>() // Permanent (Home Screen)

    private val allMessages = mutableStateListOf<ChatMessage>()
    private var currentChatTarget: String? = null

    // Popup State
    private var showConnectionRequest by mutableStateOf(false)
    private var requestSenderName by mutableStateOf("")
    private var requestSenderHandle: PeerHandle? = null
    private var requestSession: DiscoverySession? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.all { p -> p.value }) attachToWifiAware() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. GENERATE ID (4 Digits)
        val prefs: SharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        var storedId = prefs.getString("MY_ID", null)
        if (storedId == null) {
            storedId = Random.nextInt(1000, 9999).toString()
            prefs.edit().putString("MY_ID", storedId).apply()
        }
        myId = storedId
        myName = "${Build.MODEL} ($myId)"

        wifiAwareManager = getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager

        setContent {
            CAppNANTheme {
                val navController = rememberNavController()

                // CONNECTION REQUEST POPUP
                if (showConnectionRequest) {
                    AlertDialog(
                        onDismissRequest = { showConnectionRequest = false },
                        title = { Text("Friend Request") },
                        text = { Text("$requestSenderName wants to connect.") },
                        confirmButton = {
                            Button(onClick = {
                                acceptConnection()
                                showConnectionRequest = false
                            }) { Text("Allow") }
                        },
                        dismissButton = {
                            Button(onClick = { showConnectionRequest = false }) { Text("Deny") }
                        }
                    )
                }

                NavHost(navController = navController, startDestination = "home") {

                    // SCREEN 1: HOME (Friend List)
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

                    // SCREEN 2: ADD FRIEND
                    composable("add_friend") {
                        // Show devices that are NOT already friends
                        val strangers = discoveredStrangers.keys.filter { !friendsList.containsKey(it) }
                        AddFriendScreen(
                            discoveredDevices = strangers,
                            onConnectClick = { name -> sendConnectionRequest(name) },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    // SCREEN 3: CHAT
                    composable("chat") {
                        val target = currentChatTarget ?: "Unknown"
                        val msgs = allMessages.filter { it.senderName == target || (it.isFromMe && it.senderName == target) }
                        ChatScreen(
                            peerName = target,
                            messages = msgs,
                            onSendMessage = { sendMessage(it) },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
        if (wifiAwareManager != null) requestPermissions()
    }

    // --- PERMISSIONS & SETUP ---
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
                Toast.makeText(this@MainActivity, "ID: $myId Active", Toast.LENGTH_LONG).show()
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
            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray, matchFilter: List<ByteArray>) {
                val peerName = String(serviceSpecificInfo) // Name from Service Info
                // Add to Strangers list initially
                discoveredStrangers[peerName] = PeerConnection(peerHandle, subscribeSessionRef!!)
            }
            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) { handleIncomingMessage(peerHandle, message, subscribeSessionRef!!) }
        }, null)
    }

    // --- LOGIC: REQUEST / ACCEPT / CHAT ---

    private fun sendConnectionRequest(targetName: String) {
        val conn = discoveredStrangers[targetName] ?: return
        val payload = "$REQ_PREFIX$myName" // "REQ:Samsung (1234)"
        try {
            conn.session.sendMessage(conn.handle, 0, payload.toByteArray())
            Toast.makeText(this, "Request sent to $targetName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Log.e(TAG, "Req fail", e) }
    }

    private fun acceptConnection() {
        val handle = requestSenderHandle ?: return
        val session = requestSession ?: return
        val name = requestSenderName

        // 1. Add to Friends
        friendsList[name] = PeerConnection(handle, session)

        // 2. Send Acceptance ACK
        val payload = "$ACK_PREFIX$myName" // "ACK:Pixel (5678)"
        try {
            session.sendMessage(handle, 0, payload.toByteArray())
            Toast.makeText(this, "Connected with $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Log.e(TAG, "Ack fail", e) }
    }

    private fun handleIncomingMessage(handle: PeerHandle, message: ByteArray, session: DiscoverySession) {
        val text = String(message)

        // CASE 1: CONNECTION REQUEST ("REQ:Samsung (1234)")
        if (text.startsWith(REQ_PREFIX)) {
            val sender = text.removePrefix(REQ_PREFIX)
            // Update Stranger list just in case
            discoveredStrangers[sender] = PeerConnection(handle, session)

            // Show Popup
            requestSenderName = sender
            requestSenderHandle = handle
            requestSession = session
            showConnectionRequest = true
        }

        // CASE 2: CONNECTION ACCEPTED ("ACK:Pixel (5678)")
        else if (text.startsWith(ACK_PREFIX)) {
            val sender = text.removePrefix(ACK_PREFIX)
            friendsList[sender] = PeerConnection(handle, session) // Move to Friends
            runOnUiThread { Toast.makeText(this, "$sender accepted!", Toast.LENGTH_SHORT).show() }
        }

        // CASE 3: CHAT MESSAGE ("MSG:Samsung:Hello")
        else if (text.startsWith(MSG_PREFIX)) {
            try {
                val parts = text.split(":", limit = 3) // MSG : Name : Body
                if (parts.size == 3) {
                    val senderName = parts[1]
                    val body = parts[2]

                    // Ensure they are in friends list (implicit update)
                    friendsList[senderName] = PeerConnection(handle, session)

                    runOnUiThread { allMessages.add(ChatMessage(body, false, senderName)) }
                }
            } catch (e: Exception) { }
        }
    }

    private fun sendMessage(text: String) {
        val target = currentChatTarget ?: return
        val conn = friendsList[target] ?: return // Can only chat with friends

        val payload = "$MSG_PREFIX$myName:$text"
        try {
            conn.session.sendMessage(conn.handle, 0, payload.toByteArray())
            allMessages.add(ChatMessage(text, true, target))
        } catch (e: Exception) { Toast.makeText(this, "Send Failed", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() { super.onDestroy(); wifiAwareSession?.close() }
}