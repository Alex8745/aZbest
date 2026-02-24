package com.ss.azbest.ui.theme.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ss.azbest.data.MessageRepository
import com.ss.azbest.data.GENERAL_CHAT_ID
import com.ss.azbest.domain.ChatMessage
import com.ss.azbest.domain.ChatPreview
import com.ss.azbest.domain.ConnectionState
import com.ss.azbest.domain.MeshNodeInfo
import com.ss.azbest.domain.MeshtasticDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val repository: MessageRepository) : ViewModel() {

    // ── Состояние подключения ─────────────────────────────────────────────────
    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val discoveredDevices: StateFlow<List<MeshtasticDevice>> = repository.discoveredDevices

    // ── Список чатов ──────────────────────────────────────────────────────────
    val chatPreviews: StateFlow<List<ChatPreview>> = repository.chatPreviews

    // ── Ноды сети ─────────────────────────────────────────────────────────────
    val nodes: StateFlow<List<MeshNodeInfo>> = repository.nodes

    // ── Текущий открытый чат ──────────────────────────────────────────────────
    private val _currentChatId = MutableStateFlow(GENERAL_CHAT_ID)
    val currentChatId: StateFlow<String> = _currentChatId.asStateFlow()

    // Сообщения текущего чата
    private val _currentMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentMessages: StateFlow<List<ChatMessage>> = _currentMessages.asStateFlow()

    // ── Ввод текста ───────────────────────────────────────────────────────────
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    // Ошибка отправки (показывается snackbar'ом)
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    init {
        // Подписываемся на сообщения выбранного чата
        viewModelScope.launch {
            _currentChatId.collect { chatId ->
                repository.messagesFor(chatId).collect { messages ->
                    _currentMessages.value = messages
                }
            }
        }
    }

    // ── Навигация по чатам ────────────────────────────────────────────────────

    fun openChat(chatId: String) {
        _currentChatId.value = chatId
        viewModelScope.launch {
            repository.messagesFor(chatId).collect { messages ->
                _currentMessages.value = messages
            }
        }
    }

    // ── Отправка ──────────────────────────────────────────────────────────────

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return
        if (text.toByteArray().size > 200) {
            _sendError.value = "Сообщение слишком длинное (макс. 200 байт)"
            return
        }

        _inputText.value = ""
        viewModelScope.launch {
            val ok = repository.sendMessage(text, _currentChatId.value)
            if (!ok) {
                _sendError.value = "Не удалось отправить. Нода не подключена или ID не получен."
            }
        }
    }

    fun clearSendError() {
        _sendError.value = null
    }

    // ── BLE ───────────────────────────────────────────────────────────────────

    fun startScan() = repository.startScan()
    fun stopScan() = repository.stopScan()
    fun connect(address: String) = repository.connect(address)
    fun disconnect() = repository.disconnect()

    override fun onCleared() {
        super.onCleared()
        repository.disconnect()
    }
}
