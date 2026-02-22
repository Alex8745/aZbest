package com.ss.azbest.domain

data class ChatMessage(
    val id: String,
    val text: String,
    val sender: String,
    val timestamp: Long,
    val isMine: Boolean,
    val status: MessageStatus = MessageStatus.SENT
)

enum class MessageStatus {
    SENDING,
    SENT,
    FAILED
}

data class MeshtasticDevice(
    val address: String,
    val name: String,
    val rssi: Int
)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val deviceName: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
