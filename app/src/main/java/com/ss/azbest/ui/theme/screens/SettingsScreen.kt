package com.ss.azbest.ui.theme.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ss.azbest.domain.ConnectionState
import com.ss.azbest.domain.ModemPresetOption
import com.ss.azbest.ui.theme.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val loraSettings by viewModel.loraSettings.collectAsState()
    val settingsResult by viewModel.settingsResult.collectAsState()

    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(settingsResult) {
        settingsResult?.let { msg ->
            snackbarHost.showSnackbar(msg)
            viewModel.clearSettingsResult()
        }
    }

    val isConnected = connectionState is ConnectionState.Connected
    var selectedPreset by remember(loraSettings) { mutableStateOf(loraSettings.modemPreset) }
    var frequencyText by remember(loraSettings) {
        mutableStateOf(
            if (loraSettings.overrideFrequency > 0f) loraSettings.overrideFrequency.toString() else ""
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Настройки", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1C1C1E))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {

            // ── Подключение ──────────────────────────────────────────────────
            SectionLabel("Подключение")
            SettingsCard {
                SettingsRow(
                    label = "Статус",
                    value = when (connectionState) {
                        is ConnectionState.Connected ->
                            (connectionState as ConnectionState.Connected).deviceName
                        is ConnectionState.Connecting  -> "Подключение..."
                        is ConnectionState.Scanning    -> "Сканирование..."
                        is ConnectionState.Disconnected -> "Не подключено"
                        is ConnectionState.Error ->
                            (connectionState as ConnectionState.Error).message
                    },
                    valueColor = if (isConnected) Color(0xFF3DDC84) else Color(0xFF8E8E93)
                )
                if (isConnected) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.disconnect() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1C1C)),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Отключиться", color = Color(0xFFFF453A)) }
                }
            }

            Spacer(Modifier.height(20.dp))
            if (!isConnected) {
                Text(
                    "⚠ Подключитесь к ESP чтобы отправить настройки",
                    color = Color(0xFFFFCC00), fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // ── Шаблон модуляции ─────────────────────────────────────────────
            SectionLabel("Шаблон модуляции")
            SettingsCard {
                ModemPresetOption.values().forEach { preset ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedPreset == preset,
                            onClick = { selectedPreset = preset },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF0084FF))
                        )
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(preset.displayName, color = Color.White, fontSize = 15.sp)
                            Text(preset.description, color = Color(0xFF8E8E93), fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.applyLoraSettings(
                            usePreset = true,
                            preset = selectedPreset,
                            overrideFrequency = 0f
                        )
                    },
                    enabled = isConnected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0084FF),
                        disabledContainerColor = Color(0xFF2C2C2E)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isConnected) "Применить шаблон" else "Нет подключения",
                        color = if (isConnected) Color.White else Color(0xFF5C5C5E)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Частота вручную ───────────────────────────────────────────────
            SectionLabel("Частота вручную (МГц)")
            SettingsCard {
                OutlinedTextField(
                    value = frequencyText,
                    onValueChange = { v ->
                        if (v.isEmpty() || v.matches(Regex("\\d{0,4}(\\.\\d{0,3})?"))) frequencyText = v
                    },
                    placeholder = { Text("например: 868.525", color = Color(0xFF5C5C5E)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF0084FF),
                        unfocusedBorderColor = Color(0xFF3A3A3C),
                        cursorColor = Color(0xFF0084FF),
                        focusedContainerColor = Color(0xFF2C2C2E),
                        unfocusedContainerColor = Color(0xFF2C2C2E)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "EU 868: 869.525  ·  US 915: 906.875  ·  LongFast: 868.525",
                    color = Color(0xFF5C5C5E), fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))

                val freqValid = frequencyText.toFloatOrNull()?.let { it > 0f } == true
                Button(
                    onClick = {
                        viewModel.applyLoraSettings(
                            usePreset = false,
                            preset = selectedPreset,
                            overrideFrequency = frequencyText.toFloat()
                        )
                    },
                    enabled = isConnected && freqValid,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0084FF),
                        disabledContainerColor = Color(0xFF2C2C2E)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when {
                            !isConnected -> "Нет подключения"
                            !freqValid   -> "Введите частоту"
                            else         -> "Применить частоту"
                        },
                        color = if (isConnected && freqValid) Color.White else Color(0xFF5C5C5E)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── О приложении ──────────────────────────────────────────────────
            SectionLabel("О приложении")
            SettingsCard {
                SettingsRow("Протокол", "Meshtastic BLE")
                SettingsRow("Версия", "1.0.0")
                SettingsRow("Разработчик", "DaSa Labs")
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
fun SectionLabel(title: String) {
    Text(
        title.uppercase(), color = Color(0xFF8E8E93),
        fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFF1C1C1E)) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun SettingsRow(label: String, value: String, valueColor: Color = Color(0xFF8E8E93)) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White, fontSize = 15.sp)
        Text(value, color = valueColor, fontSize = 15.sp)
    }
}
