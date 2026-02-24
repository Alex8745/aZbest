package com.ss.azbest.ui.theme.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ss.azbest.data.GENERAL_CHAT_ID
import com.ss.azbest.domain.ConnectionState
import com.ss.azbest.domain.MeshtasticDevice
import com.ss.azbest.ui.theme.components.InputBar
import com.ss.azbest.ui.theme.components.MessageBubble
import com.ss.azbest.ui.theme.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    chatId: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.currentMessages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val sendError by viewModel.sendError.collectAsState()

    val listState = rememberLazyListState()
    val snackbarHost = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Скролл к последнему сообщению
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Показываем ошибку отправки
    LaunchedEffect(sendError) {
        sendError?.let { error ->
            snackbarHost.showSnackbar(error)
            viewModel.clearSendError()
        }
    }

    val title = if (chatId == GENERAL_CHAT_ID) "Общий канал" else chatId

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Назад",
                            tint = Color(0xFF0084FF)
                        )
                    }
                },
                title = {
                    Column {
                        Text(title, color = Color.White, fontSize = 16.sp)
                        Text(
                            text = when (connectionState) {
                                is ConnectionState.Connected -> "подключено"
                                is ConnectionState.Connecting -> "подключение..."
                                else -> "не подключено"
                            },
                            color = if (connectionState is ConnectionState.Connected)
                                Color(0xFF3DDC84) else Color(0xFF8E8E93),
                            fontSize = 12.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1C1C1E))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
        ) {
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
                enabled = connectionState is ConnectionState.Connected,
                modifier = Modifier.imePadding()
            )
        }
    }
}

// DeviceListDialog остаётся здесь и используется из ChatsListScreen через import
@Composable
fun DeviceListDialog(
    devices: List<MeshtasticDevice>,
    isScanning: Boolean,
    onDeviceSelected: (MeshtasticDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите устройство", color = Color.White) },
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
                        Text("Сканирование...", color = Color.White)
                    }
                }

                if (devices.isEmpty() && !isScanning) {
                    Text(
                        "Устройства не найдены",
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
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(device.name, color = Color.White, fontSize = 16.sp)
                                Text(
                                    text = if (device.rssi == Int.MIN_VALUE) "RSSI: н/д"
                                           else "RSSI: ${device.rssi} dBm",
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
                Text("Отмена", color = Color(0xFF0084FF))
            }
        },
        containerColor = Color(0xFF1C1C1E)
    )
}
