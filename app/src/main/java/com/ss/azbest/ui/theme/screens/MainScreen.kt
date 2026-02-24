package com.ss.azbest.ui.theme.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.ss.azbest.ui.theme.viewmodel.ChatViewModel
import androidx.compose.ui.unit.dp

enum class AppTab(val label: String, val icon: String) {
    CHATS("Чаты", "💬"),
    NODES("Ноды", "📡"),
    SETTINGS("Настройки", "⚙️")
}

@Composable
fun MainScreen(viewModel: ChatViewModel) {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.CHATS) }
    // null = список чатов, non-null = открытый чат
    var openedChatId by rememberSaveable { mutableStateOf<String?>(null) }

    // Если открыт конкретный чат — показываем его без bottom bar
    if (currentTab == AppTab.CHATS && openedChatId != null) {
        ChatDetailScreen(
            chatId = openedChatId!!,
            viewModel = viewModel,
            onBack = { openedChatId = null }
        )
        return
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF1C1C1E),
                tonalElevation = 0.dp
            ) {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {
                            Text(tab.icon, fontSize = 20.sp)
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                color = if (currentTab == tab) Color(0xFF0084FF)
                                        else Color(0xFF8E8E93)
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF0084FF),
                            unselectedIconColor = Color(0xFF8E8E93),
                            indicatorColor = Color(0xFF2C2C2E)
                        )
                    )
                }
            }
        }
    ) { padding ->
        when (currentTab) {
            AppTab.CHATS -> ChatsListScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
                onChatSelected = { chatId ->
                    viewModel.openChat(chatId)
                    openedChatId = chatId
                }
            )
            AppTab.NODES -> NodesScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
            AppTab.SETTINGS -> SettingsScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
        }
    }
}
