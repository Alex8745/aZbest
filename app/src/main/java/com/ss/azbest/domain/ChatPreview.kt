package com.ss.azbest.domain

data class ChatPreview(
    val chatId: String,
    val title: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val isGeneral: Boolean = false,
    val unreadCount: Int = 0       // непрочитанных сообщений
)
