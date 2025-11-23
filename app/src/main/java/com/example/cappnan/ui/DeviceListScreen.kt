package com.example.cappnan.ui

import android.net.wifi.aware.PeerHandle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DeviceListScreen(
    devices: Map<PeerHandle, String>,
    onDeviceClick: (peerHandle: PeerHandle, name: String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (devices.isEmpty()) {
            Text(text = "Searching for devices...")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(devices.entries.toList()) { (peerHandle, name) ->
                    Text(
                        text = name,
                        modifier = Modifier
                            .fillParentMaxWidth()
                            .padding(16.dp)
                            .clickable { onDeviceClick(peerHandle, name) }
                    )
                }
            }
        }
    }
}
