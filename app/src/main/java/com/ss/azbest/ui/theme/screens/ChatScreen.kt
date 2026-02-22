package com.ss.azbest.ui.theme.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ss.azbest.domain.ConnectionState
import com.ss.azbest.ui.theme.components.InputBar
import com.ss.azbest.ui.theme.components.MessageBubble
import com.ss.azbest.ui.theme.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()

    val listState = rememberLazyListState()
    var showDeviceDialog by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Meshtastic Chat",
                            color = Color.White,
                            fontSize = 18.sp
                        )
                        Text(
                            text = when (connectionState) {
                                is ConnectionState.Connected -> (connectionState as ConnectionState.Connected).deviceName
                                is ConnectionState.Connecting -> "Connecting..."
                                is ConnectionState.Scanning -> "Scanning..."
                                is ConnectionState.Disconnected -> "Not connected"
                                is ConnectionState.Error -> (connectionState as ConnectionState.Error).message
                            },
                            color = Color(0xFF8E8E93),
                            fontSize = 13.sp
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            runCatching {
                                when (connectionState) {
                                    is ConnectionState.Connected -> viewModel.disconnect()
                                    else -> {
                                        viewModel.startScan()
                                        showDeviceDialog = true
                                    }
                                }
                            }
                        }
                    ) {
                        Text(
                            text = when (connectionState) {
                                is ConnectionState.Connected -> "Disconnect"
                                else -> "Connect"
                            },
                            color = Color(0xFF0084FF)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1C1C1E)
                )
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (connectionState is ConnectionState.Connected) Color(0xFF0C2A1F) else Color(0xFF2C2C2E)
            ) {
                Text(
                    text = when (connectionState) {
                        is ConnectionState.Connected -> "ESP BLE: подключено к ${(connectionState as ConnectionState.Connected).deviceName}"
                        is ConnectionState.Connecting -> "ESP BLE: подключение..."
                        else -> "ESP BLE: не подключено"
                    },
                    color = if (connectionState is ConnectionState.Connected) Color(0xFF3DDC84) else Color(0xFFC7C7CC),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }

            InputBar(
                text = inputText,
                onTextChange = viewModel::updateInputText,
                onSend = viewModel::sendMessage,
                enabled = true,
                modifier = Modifier.imePadding()
            )
        }
    }

    if (showDeviceDialog) {
        DeviceListDialog(
            devices = discoveredDevices,
            isScanning = connectionState is ConnectionState.Scanning,
            onDeviceSelected = { device ->
                viewModel.stopScan()
                viewModel.connect(device.address)
                showDeviceDialog = false
            },
            onDismiss = {
                viewModel.stopScan()
                showDeviceDialog = false
            }
        )
    }
}

@Composable
fun DeviceListDialog(
    devices: List<com.ss.azbest.domain.MeshtasticDevice>,
    isScanning: Boolean,
    onDeviceSelected: (com.ss.azbest.domain.MeshtasticDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Select Meshtastic Device", color = Color.White)
        },
        text = {
            Column {
                if (isScanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF0084FF)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Scanning...", color = Color.White)
                    }
                }

                if (devices.isEmpty() && !isScanning) {
                    Text(
                        "No devices found",
                        color = Color(0xFF8E8E93),
                        modifier = Modifier.padding(8.dp)
                    )
                }


                                        LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 320.dp)
                                        ) {
                                    items(devices, key = { it.address }) { device ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clickable { onDeviceSelected(device) },
                                            colors = CardDefaults.cardColors(
                                                containerColor = Color(0xFF2C2C2E)
                                            )
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp)
                                            ) {
                                                Text(
                                                    text = device.name,
                                                    color = Color.White,
                                                    fontSize = 16.sp
                                                )
                                                Text(
                                                    text = if (device.rssi == Int.MIN_VALUE) "RSSI: n/a" else "RSSI: ${device.rssi} dBm",
                                                    color = Color(0xFF8E8E93),
                                                    fontSize = 13.sp
                                                )
                                            }
                                        }
                                    }
                                }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = Color(0xFF0084FF))
                        }
                    },
                    containerColor = Color(0xFF1C1C1E)
                    )
                }