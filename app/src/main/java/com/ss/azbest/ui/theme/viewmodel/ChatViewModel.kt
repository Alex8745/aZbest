package com.ss.azbest.ui.theme.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ss.azbest.data.MessageRepository
import com.ss.azbest.data.GENERAL_CHAT_ID
import com.ss.azbest.domain.ChatMessage
import com.ss.azbest.domain.ChatPreview
import com.ss.azbest.domain.ConnectionState
import com.ss.azbest.domain.LoraSettings
import com.ss.azbest.domain.MeshNodeInfo
import com.ss.azbest.domain.MeshtasticDevice
import com.ss.azbest.domain.ModemPresetOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val repository: MessageRepository) : ViewModel() {

    // ── Подключение ───────────────────────────────────────────────────────────
    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val discoveredDevices: StateFlow<List<MeshtasticDevice>> = repository.discoveredDevices

    // ── Чаты ──────────────────────────────────────────────────────────────────
    val chatPreviews: StateFlow<List<ChatPreview>> = repository.chatPreviews

    // ── Ноды ──────────────────────────────────────────────────────────────────
    val nodes: StateFlow<List<MeshNodeInfo>> = repository.nodes

    // ── Текущий открытый чат ──────────────────────────────────────────────────
    private val _currentChatId = MutableStateFlow(GENERAL_CHAT_ID)
    val currentChatId: StateFlow<String> = _currentChatId.asStateFlow()

    private val _currentMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentMessages: StateFlow<List<ChatMessage>> = _currentMessages.asStateFlow()

    // ── Ввод ──────────────────────────────────────────────────────────────────
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    // ── Непрочитанные ─────────────────────────────────────────────────────────

    // ── LoRa настройки ────────────────────────────────────────────────────────
    private val _loraSettings = MutableStateFlow(LoraSettings())
    val loraSettings: StateFlow<LoraSettings> = _loraSettings.asStateFlow()

    // Результат применения настроек (показывается в snackbar)
    private val _settingsResult = MutableStateFlow<String?>(null)
    val settingsResult: StateFlow<String?> = _settingsResult.asStateFlow()

    init {
        viewModelScope.launch {
            _currentChatId.collect { chatId ->
                repository.messagesFor(chatId).collect { messages ->
                    _currentMessages.value = messages
                }
            }
        }
        // Обновляем бейдж когда меняются превью чатов
        viewModelScope.launch {
            repository.chatPreviews.collect {
                _totalUnread.value = repository.totalUnread()
            }
        }
    }

    // ── Навигация ─────────────────────────────────────────────────────────────

    fun openChat(chatId: String) {
        _currentChatId.value = chatId
        repository.activeChatId = chatId   // сбрасывает счётчик непрочитанных
        _totalUnread.value = repository.totalUnread()
        viewModelScope.launch {
            repository.messagesFor(chatId).collect { messages ->
                _currentMessages.value = messages
            }
        }
    }

    // ── Отправка ──────────────────────────────────────────────────────────────

    fun updateInputText(text: String) { _inputText.value = text }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return
        if (text.toByteArray().size > 200) {
            _sendError.value = "Слишком длинное (макс. ~200 байт)"
            return
        }
        _inputText.value = ""
        viewModelScope.launch {
            val ok = repository.sendMessage(text, _currentChatId.value)
            if (!ok) _sendError.value = "Не удалось отправить. Проверьте подключение."
        }
    }

    fun clearSendError() { _sendError.value = null }

    // ── Непрочитанные ─────────────────────────────────────────────────────────
    private val _totalUnread = MutableStateFlow(0)
    val totalUnread: StateFlow<Int> = _totalUnread.asStateFlow()

    // ── LoRa настройки ────────────────────────────────────────────────────────

    fun applyLoraSettings(
        usePreset: Boolean,
        preset: ModemPresetOption,
        overrideFrequency: Float
    ) {
        val result = repository.sendLoraConfig(
            usePreset = usePreset,
            presetValue = preset.protoValue,
            overrideFrequency = overrideFrequency
        )

        if (result.isSuccess) {
            _loraSettings.value = LoraSettings(
                usePreset = usePreset,
                modemPreset = preset,
                overrideFrequency = overrideFrequency
            )
            _settingsResult.value = if (usePreset)
                "✓ Шаблон «${preset.displayName}» отправлен. ESP перезагружается..."
            else
                "✓ Частота ${overrideFrequency} МГц отправлена. ESP перезагружается..."
        } else {
            _settingsResult.value = "✗ Ошибка: ${result.exceptionOrNull()?.message}"
        }
    }

    fun clearSettingsResult() { _settingsResult.value = null }

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
