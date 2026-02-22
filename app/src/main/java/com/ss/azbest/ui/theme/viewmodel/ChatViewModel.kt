package com.ss.azbest.ui.theme.viewmodel


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ss.azbest.data.MessageRepository
import com.ss.azbest.domain.ChatMessage
import com.ss.azbest.domain.ConnectionState
import com.ss.azbest.domain.MeshtasticDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val repository: MessageRepository) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> = repository.messages
    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val discoveredDevices: StateFlow<List<MeshtasticDevice>> = repository.discoveredDevices

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        if (text.toByteArray().size > 200) {
            return // Enforce 200 byte limit
        }

        viewModelScope.launch {
            repository.sendMessage(text)
            _inputText.value = ""
        }
    }

    fun startScan() {
        repository.startScan()
    }

    fun stopScan() {
        repository.stopScan()
    }

    fun connect(deviceAddress: String) {
        repository.connect(deviceAddress)
    }

    fun disconnect() {
        repository.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnect()
    }
}
