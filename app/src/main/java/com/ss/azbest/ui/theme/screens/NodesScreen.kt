package com.ss.azbest.ui.theme.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ss.azbest.domain.MeshNodeInfo
import com.ss.azbest.ui.theme.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val nodes by viewModel.nodes.collectAsState()

    Scaffold(
        modifier = modifier,
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Ноды в сети",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1C1C1E))
            )
        }
    ) { padding ->
        if (nodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📡", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Ноды не обнаружены",
                        color = Color(0xFF8E8E93),
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Подключитесь к ESP для получения\nинформации о сети",
                        color = Color(0xFF5C5C5E),
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(nodes, key = { it.nodeId }) { node ->
                    NodeItem(node = node)
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = Color(0xFF2C2C2E),
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeItem(node: MeshNodeInfo) {
    val timeFormat = SimpleDateFormat("HH:mm dd.MM", Locale.getDefault())
    val snrColor = when {
        node.snr > 5f -> Color(0xFF3DDC84)   // хороший сигнал
        node.snr > 0f -> Color(0xFFFFCC00)   // средний
        else -> Color(0xFFFF453A)             // плохой
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Аватар с инициалами
        Surface(
            modifier = Modifier.size(50.dp),
            shape = CircleShape,
            color = Color(0xFF2C2C2E)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = node.shortName.take(2).uppercase().ifEmpty { "?" },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Имя ноды
            Text(
                text = node.longName.ifEmpty { node.nodeId },
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            // Node ID
            Text(
                text = node.nodeId,
                color = Color(0xFF8E8E93),
                fontSize = 13.sp
            )
            if (node.lastHeard > 0) {
                Text(
                    text = "Последний раз: ${timeFormat.format(Date(node.lastHeard))}",
                    color = Color(0xFF5C5C5E),
                    fontSize = 12.sp
                )
            }
        }

        // SNR индикатор
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "SNR",
                color = Color(0xFF8E8E93),
                fontSize = 11.sp
            )
            Text(
                text = "${node.snr.toInt()} dB",
                color = snrColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
