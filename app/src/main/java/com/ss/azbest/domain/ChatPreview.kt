package com.ss.azbest.domain

data class ChatPreview(
    val chatId: String,      // "general" или "!nodeId"
    val title: String,       // "Общий канал" или имя ноды
    val lastMessage: String,
    val lastTimestamp: Long,
    val isGeneral: Boolean = false
)
