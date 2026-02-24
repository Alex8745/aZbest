package com.ss.azbest.ui.theme.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ss.azbest.domain.ConnectionState
import com.ss.azbest.ui.theme.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()

    Scaffold(
        modifier = modifier,
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Настройки",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1C1C1E))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Секция: Подключение
            SettingsSection(title = "Подключение") {
                SettingsRow(
                    label = "Статус",
                    value = when (connectionState) {
                        is ConnectionState.Connected ->
                            (connectionState as ConnectionState.Connected).deviceName
                        is ConnectionState.Connecting -> "Подключение..."
                        is ConnectionState.Scanning -> "Сканирование..."
                        is ConnectionState.Disconnected -> "Не подключено"
                        is ConnectionState.Error ->
                            (connectionState as ConnectionState.Error).message
                    },
                    valueColor = if (connectionState is ConnectionState.Connected)
                        Color(0xFF3DDC84) else Color(0xFF8E8E93)
                )
                if (connectionState is ConnectionState.Connected) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.disconnect() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2C2C2E)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Отключиться", color = Color(0xFFFF453A))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Секция: О приложении
            SettingsSection(title = "О приложении") {
                SettingsRow(label = "Протокол", value = "Meshtastic BLE")
                SettingsRow(label = "Версия", value = "1.0.0")
                SettingsRow(label = "Разработчик", value = "DaSa Labs")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Заглушка для будущих настроек
            SettingsSection(title = "Канал (скоро)") {
                SettingsRow(label = "Частота", value = "868.525 МГц")
                SettingsRow(label = "Режим", value = "Medium Fast")
                SettingsRow(label = "Слот", value = "0 (Primary)")
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        text = title.uppercase(),
        color = Color(0xFF8E8E93),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFF1C1C1E)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
    valueColor: Color = Color(0xFF8E8E93)
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White, fontSize = 15.sp)
        Text(value, color = valueColor, fontSize = 15.sp)
    }
}
