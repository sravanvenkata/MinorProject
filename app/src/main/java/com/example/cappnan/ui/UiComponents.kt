package com.example.cappnan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cappnan.ChatMessage // Now imports correctly from AppUtils.kt
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.window.Dialog
import com.example.cappnan.generateQrCodeBitmap
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.RGBLuminanceSource

// --- SCREEN 1: HOME (FRIEND LIST) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    myId: String,
    myName: String,
    myPublicKey: String, // We now pass the public key to the UI
    friends: List<String>,
    onChatClick: (String) -> Unit,
    onAddFriendClick: () -> Unit
) {
    var showQrDialog by remember { mutableStateOf(false) }

    // This is the specific data format your friend's phone will read
    val qrDataString = "MESH:$myId:$myName:$myPublicKey"

    if (showQrDialog) {
        Dialog(onDismissRequest = { showQrDialog = false }) {
            Card(
                modifier = Modifier.padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Scan to add me", fontWeight = FontWeight.Bold, color = Color.Black)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Generate and show the QR Image
                    val qrBitmap = remember { generateQrCodeBitmap(qrDataString) }
                    qrBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "My QR Code",
                            modifier = Modifier.size(250.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { showQrDialog = false }) { Text("Close") }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My ID: $myId") },
                actions = {
                    // New Button to show QR Code
                    IconButton(onClick = { showQrDialog = true }) {
                        Icon(Icons.Default.Share, contentDescription = "Show QR Code")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFriendClick) {
                Icon(Icons.Default.Add, contentDescription = "Add Friend")
            }
        }
    ) { padding ->
        if (friends.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No friends yet. Click + to add.")
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(friends) { name ->
                    FriendItem(name = name, onClick = { onChatClick(name) })
                }
            }
        }
    }
}

// --- SCREEN 2: ADD FRIEND ---


// --- SCREEN 3: CHAT ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerName: String,
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    onBack: () -> Unit
) {
    var textState by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(peerName) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(modifier = Modifier.weight(1f).padding(8.dp), state = listState) {
                items(messages) { msg -> MessageBubble(msg) }
            }
            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = textState, onValueChange = { textState = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("Type...") },
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                )
                IconButton(onClick = { if(textState.isNotBlank()) { onSendMessage(textState); textState = "" } }) {
                    Icon(Icons.Default.Send, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// --- ITEMS ---
@Composable
fun FriendItem(name: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { onClick() },
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, null, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}



@Composable
fun MessageBubble(message: ChatMessage) {
    val isMe = message.isFromMe
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(4.dp).widthIn(max = 280.dp)
        ) {
            Text(message.text, modifier = Modifier.padding(10.dp), color = if (isMe) Color.White else Color.Black)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanQrScreen(
    onQrScanned: (String) -> Unit,
    onBack: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasScanned by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Friend's QR") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { context ->
                val previewView = PreviewView(context)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                        if (!hasScanned) {
                            val qrResult = scanImage(imageProxy)
                            if (qrResult != null && qrResult.startsWith("MESH:")) {
                                hasScanned = true // Stop scanning once we find a valid code
                                onQrScanned(qrResult)
                            }
                        }
                        imageProxy.close()
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                        )
                    } catch (e: Exception) { }
                }, ContextCompat.getMainExecutor(context))
                previewView
            }
        )
    }
}

// Helper function to read the QR Code from the camera frame
// Helper function to read the QR Code from the camera frame
private fun scanImage(imageProxy: ImageProxy): String? {
    return try {
        // toBitmap() automatically fixes camera rotation and row padding!
        val bitmap = imageProxy.toBitmap()
        val intArray = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val source = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        val result = MultiFormatReader().decode(binaryBitmap)
        result.text
    } catch (e: Exception) {
        null // Keep scanning if no QR code is found in this specific frame
    }
}