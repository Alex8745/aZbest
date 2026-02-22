package com.ss.azbest.ui.theme.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ss.azbest.domain.ChatMessage
import com.ss.azbest.domain.MessageStatus
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MessageBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val timeString = dateFormat.format(Date(message.timestamp))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start
        ) {
            if (!message.isMine) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6C7A89),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 2.dp, start = 12.dp)
                )
            }

            Box(
                modifier = Modifier
                    .background(
                        color = if (message.isMine) Color(0xFF0084FF) else Color(0xFF2C2C2E),
                        shape = RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (message.isMine) 18.dp else 4.dp,
                            bottomEnd = if (message.isMine) 4.dp else 18.dp
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Column {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontSize = 15.sp
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = timeString,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        if (message.isMine) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when (message.status) {
                                    MessageStatus.SENDING -> "⏱"
                                    MessageStatus.SENT -> "✓"
                                    MessageStatus.FAILED -> "✗"
                                },
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
