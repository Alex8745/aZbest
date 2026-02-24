package com.ss.azbest.data

import android.content.Context
import com.ss.azbest.domain.ChatMessage
import com.ss.azbest.domain.ChatPreview
import com.ss.azbest.domain.ConnectionState
import com.ss.azbest.domain.MeshNodeInfo
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

const val GENERAL_CHAT_ID = "general"

class MessageRepository(
    private val transport: MeshtasticTransport,
    context: Context
) {
    private val storage = ChatStorage(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    // Сообщения по chatId (в памяти)
    private val _messagesByChatId = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())

    // Список превью чатов для главного экрана
    private val _chatPreviews = MutableStateFlow<List<ChatPreview>>(emptyList())
    val chatPreviews: StateFlow<List<ChatPreview>> = _chatPreviews.asStateFlow()

    // Известные ноды сети
    private val _nodes = MutableStateFlow<List<MeshNodeInfo>>(emptyList())
    val nodes: StateFlow<List<MeshNodeInfo>> = _nodes.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = transport.connectionState
    val discoveredDevices: StateFlow<List<MeshtasticDevice>> = transport.discoveredDevices

    init {
        loadPersistedMessages()
        observeIncomingMessages()
        observeNodes()
    }

    // ── Загрузка сохранённых сообщений ────────────────────────────────────────

    private fun loadPersistedMessages() {
        scope.launch {
            val allIds = storage.getKnownChatIds().toMutableSet()
            // Всегда показываем "general" даже если пустой
            allIds.add(GENERAL_CHAT_ID)

            val map = allIds.associateWith { chatId ->
                storage.loadMessages(chatId).sortedBy { it.timestamp }
            }
            _messagesByChatId.value = map
            refreshPreviews(map)
        }
    }

    // ── Получение сообщений конкретного чата ──────────────────────────────────

    fun messagesFor(chatId: String): StateFlow<List<ChatMessage>> {
        // Ленивая инициализация потока для конкретного чата
        return object : StateFlow<List<ChatMessage>> {
            private val flow = MutableStateFlow(
                _messagesByChatId.value[chatId] ?: emptyList()
            ).also { mf ->
                scope.launch {
                    _messagesByChatId.collect { map ->
                        mf.value = map[chatId] ?: emptyList()
                    }
                }
            }
            override val replayCache get() = flow.replayCache
            override val value get() = flow.value
            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<List<ChatMessage>>) =
                flow.collect(collector)
        }
    }

    // ── Наблюдение за входящими ───────────────────────────────────────────────

    private fun observeIncomingMessages() {
        scope.launch {
            transport.incomingMessages.collect { incoming ->
                incoming.forEach { message ->
                    addMessage(message)
                }
            }
        }
    }

    private fun observeNodes() {
        scope.launch {
            transport.knownNodes.collect { nodes ->
                _nodes.value = nodes
            }
        }
    }

    // ── Добавление сообщения ──────────────────────────────────────────────────

    private fun addMessage(message: ChatMessage) {
        val current = _messagesByChatId.value.toMutableMap()
        val chatMessages = current[message.chatId]?.toMutableList() ?: mutableListOf()

        // Дедупликация по id
        if (chatMessages.none { it.id == message.id }) {
            chatMessages.add(message)
            // Сортировка по времени — ключевой фикс проблемы с timestamp
            chatMessages.sortBy { it.timestamp }
            current[message.chatId] = chatMessages
            _messagesByChatId.value = current
            storage.saveMessages(message.chatId, chatMessages)
            refreshPreviews(current)
        }
    }

    private fun refreshPreviews(map: Map<String, List<ChatMessage>>) {
        val previews = map.entries
            .filter { it.key == GENERAL_CHAT_ID || it.value.isNotEmpty() }
            .map { (chatId, messages) ->
                val last = messages.lastOrNull()
                ChatPreview(
                    chatId = chatId,
                    title = if (chatId == GENERAL_CHAT_ID) "Общий канал" else chatId,
                    lastMessage = last?.text ?: "Нет сообщений",
                    lastTimestamp = last?.timestamp ?: 0L,
                    isGeneral = chatId == GENERAL_CHAT_ID
                )
            }
            // Общий канал всегда первым, остальные по времени последнего сообщения
            .sortedWith(compareByDescending<ChatPreview> { it.isGeneral }.thenByDescending { it.lastTimestamp })
        _chatPreviews.value = previews
    }

    // ── Отправка ──────────────────────────────────────────────────────────────

    suspend fun sendMessage(text: String, chatId: String = GENERAL_CHAT_ID): Boolean {
        val tempId = UUID.randomUUID().toString()
        val message = ChatMessage(
            id = tempId,
            chatId = chatId,
            text = text,
            sender = "Me",
            timestamp = System.currentTimeMillis(),
            isMine = true,
            status = MessageStatus.SENDING
        )
        addMessage(message)

        val result = transport.sendMessage(text, chatId)

        // Обновляем статус после отправки
        val current = _messagesByChatId.value.toMutableMap()
        val updated = current[chatId]?.map {
            if (it.id == tempId) {
                it.copy(status = if (result.isSuccess) MessageStatus.SENT else MessageStatus.FAILED)
            } else it
        } ?: return result.isSuccess

        current[chatId] = updated
        _messagesByChatId.value = current
        storage.saveMessages(chatId, updated)

        return result.isSuccess
    }

    // ── BLE управление ────────────────────────────────────────────────────────

    fun startScan() = transport.startScan()
    fun stopScan() = transport.stopScan()
    fun connect(address: String) = transport.connect(address)
    fun disconnect() = transport.disconnect()

    fun sendLoraConfig(
        usePreset: Boolean,
        presetValue: Int,
        overrideFrequency: Float
    ): Result<Unit> = transport.sendLoraConfig(usePreset, presetValue, overrideFrequency)
}
