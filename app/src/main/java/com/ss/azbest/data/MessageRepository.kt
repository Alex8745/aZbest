package com.ss.azbest.data

import com.ss.azbest.domain.ChatMessage
import com.ss.azbest.domain.ConnectionState
import com.ss.azbest.domain.MessageStatus
import com.ss.azbest.domain.MeshtasticDevice
import com.ss.azbest.transport.MeshtasticTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MessageRepository(private val transport: MeshtasticTransport) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    val connectionState = transport.connectionState
    val discoveredDevices = transport.discoveredDevices

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        observeIncomingMessages()
    }

    private fun observeIncomingMessages() {
        scope.launch {
            transport.incomingMessages.collect { incoming ->
                val current = _messages.value.toMutableList()
                incoming.forEach { msg ->
                    if (current.none { it.id == msg.id }) {
                        current.add(msg)
                    }
                }
                current.sortBy { it.timestamp }
                _messages.value = current
            }
        }
    }

    fun startScan() {
        transport.startScan()
    }

    fun stopScan() {
        transport.stopScan()
    }

    fun connect(deviceAddress: String) {
        transport.connect(deviceAddress)
    }

    fun disconnect() {
        transport.disconnect()
    }

    suspend fun sendMessage(text: String) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            sender = "Me",
            timestamp = System.currentTimeMillis(),
            isMine = true,
            status = MessageStatus.SENDING
        )

        val current = _messages.value.toMutableList()
        current.add(message)
        _messages.value = current

        val result = transport.sendMessage(text)

        if (result.isFailure) {
            val updated = _messages.value.map {
                if (it.id == message.id) {
                    it.copy(status = MessageStatus.FAILED)
                } else it
            }
            _messages.value = updated
        } else {
            val updated = _messages.value.map {
                if (it.id == message.id) {
                    it.copy(status = MessageStatus.SENT)
                } else it
            }
            _messages.value = updated
        }
    }
}