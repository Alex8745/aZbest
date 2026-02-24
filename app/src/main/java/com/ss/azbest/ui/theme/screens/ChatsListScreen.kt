package com.ss.azbest.ui.theme.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ss.azbest.domain.ChatPreview
import com.ss.azbest.domain.ConnectionState
import com.ss.azbest.ui.theme.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    onChatSelected: (String) -> Unit
) {
    val chatPreviews by viewModel.chatPreviews.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    var showDeviceDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("aZbest", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = when (connectionState) {
                                is ConnectionState.Connected -> (connectionState as ConnectionState.Connected).deviceName
                                is ConnectionState.Connecting -> "Подключение..."
                                is ConnectionState.Scanning -> "Сканирование..."
                                is ConnectionState.Disconnected -> "Не подключено"
                                is ConnectionState.Error -> (connectionState as ConnectionState.Error).message
                            },
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        when (connectionState) {
                            is ConnectionState.Connected -> viewModel.disconnect()
                            else -> {
                                viewModel.startScan()
                                showDeviceDialog = true
                            }
                        }
                    }) {
                        Text(
                            text = when (connectionState) {
                                is ConnectionState.Connected -> "Отключить"
                                else -> "Подключить"
                            },
                            color = Color(0xFF0084FF)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1C1C1E))
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(chatPreviews, key = { it.chatId }) { preview ->
                ChatPreviewItem(
                    preview = preview,
                    onClick = { onChatSelected(preview.chatId) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 72.dp),
                    color = Color(0xFF2C2C2E),
                    thickness = 0.5.dp
                )
            }
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
private fun ChatPreviewItem(preview: ChatPreview, onClick: () -> Unit) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val hasUnread = preview.unreadCount > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Аватар
        Surface(
            modifier = Modifier.size(50.dp),
            shape = CircleShape,
            color = if (preview.isGeneral) Color(0xFF0A84FF) else Color(0xFF2C2C2E)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (preview.isGeneral) "📢" else preview.title.take(1).uppercase(),
                    fontSize = if (preview.isGeneral) 22.sp else 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Текст
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preview.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                // Время + бейдж
                Column(horizontalAlignment = Alignment.End) {
                    if (preview.lastTimestamp > 0) {
                        Text(
                            text = timeFormat.format(Date(preview.lastTimestamp)),
                            color = if (hasUnread) Color(0xFF0084FF) else Color(0xFF8E8E93),
                            fontSize = 12.sp
                        )
                    }
                    if (hasUnread) {
                        Spacer(Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFFF3B30), CircleShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (preview.unreadCount > 99) "99+" else preview.unreadCount.toString(),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            // Непрочитанное — жирный белый, прочитанное — серый
            Text(
                text = preview.lastMessage,
                color = if (hasUnread) Color.White else Color(0xFF8E8E93),
                fontSize = 14.sp,
                fontWeight = if (hasUnread) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
