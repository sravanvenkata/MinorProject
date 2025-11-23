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

// --- SCREEN 1: HOME (FRIEND LIST) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    myId: String,
    friends: List<String>,
    onChatClick: (String) -> Unit,
    onAddFriendClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My ID: $myId") },
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFriendScreen(
    discoveredDevices: List<String>,
    onConnectClick: (String) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nearby Strangers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }
    ) { padding ->
        if (discoveredDevices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Scanning...")
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(discoveredDevices) { name ->
                    DiscoveredItem(name = name, onAdd = { onConnectClick(name) })
                }
            }
        }
    }
}

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
fun DiscoveredItem(name: String, onAdd: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAdd) { Text("Connect") }
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