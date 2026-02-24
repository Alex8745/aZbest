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

    private val _messagesByChatId = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())

    private val _chatPreviews = MutableStateFlow<List<ChatPreview>>(emptyList())
    val chatPreviews: StateFlow<List<ChatPreview>> = _chatPreviews.asStateFlow()

    private val _nodes = MutableStateFlow<List<MeshNodeInfo>>(emptyList())
    val nodes: StateFlow<List<MeshNodeInfo>> = _nodes.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = transport.connectionState
    val discoveredDevices: StateFlow<List<MeshtasticDevice>> = transport.discoveredDevices

    // Счётчик непрочитанных по chatId
    private val unreadCounts = mutableMapOf<String, Int>()

    // ID чата который сейчас открыт (обновляется из ViewModel)
    var activeChatId: String? = null
        set(value) {
            field = value
            // Сброс счётчика когда открываем чат
            if (value != null) {
                unreadCounts[value] = 0
                refreshPreviews(_messagesByChatId.value)
            }
        }

    init {
        loadPersistedMessages()
        observeIncomingMessages()
        observeNodes()
    }

    private fun loadPersistedMessages() {
        scope.launch {
            val allIds = storage.getKnownChatIds().toMutableSet()
            allIds.add(GENERAL_CHAT_ID)
            val map = allIds.associateWith { chatId ->
                storage.loadMessages(chatId).sortedBy { it.timestamp }
            }
            _messagesByChatId.value = map
            refreshPreviews(map)
        }
    }

    fun messagesFor(chatId: String): StateFlow<List<ChatMessage>> {
        return object : StateFlow<List<ChatMessage>> {
            private val flow = MutableStateFlow(
                _messagesByChatId.value[chatId] ?: emptyList()
            ).also { mf ->
                scope.launch {
                    _messagesByChatId.collect { map -> mf.value = map[chatId] ?: emptyList() }
                }
            }
            override val replayCache get() = flow.replayCache
            override val value get() = flow.value
            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<List<ChatMessage>>) =
                flow.collect(collector)
        }
    }

    private fun observeIncomingMessages() {
        scope.launch {
            transport.incomingMessages.collect { incoming ->
                incoming.forEach { message -> addMessage(message, fromRemote = true) }
            }
        }
    }

    private fun observeNodes() {
        scope.launch {
            transport.knownNodes.collect { nodes -> _nodes.value = nodes }
        }
    }

    private fun addMessage(message: ChatMessage, fromRemote: Boolean = false) {
        val current = _messagesByChatId.value.toMutableMap()
        val chatMessages = current[message.chatId]?.toMutableList() ?: mutableListOf()

        if (chatMessages.none { it.id == message.id }) {
            chatMessages.add(message)
            chatMessages.sortBy { it.timestamp }
            current[message.chatId] = chatMessages
            _messagesByChatId.value = current
            storage.saveMessages(message.chatId, chatMessages)

            // Увеличиваем счётчик непрочитанных если чат не открыт и сообщение входящее
            if (fromRemote && !message.isMine && activeChatId != message.chatId) {
                unreadCounts[message.chatId] = (unreadCounts[message.chatId] ?: 0) + 1
            }

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
                    isGeneral = chatId == GENERAL_CHAT_ID,
                    unreadCount = unreadCounts[chatId] ?: 0
                )
            }
            .sortedWith(compareByDescending<ChatPreview> { it.isGeneral }.thenByDescending { it.lastTimestamp })
        _chatPreviews.value = previews
    }

    // Общее количество непрочитанных (для бейджа на вкладке)
    fun totalUnread(): Int = unreadCounts.values.sum()

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

        val current = _messagesByChatId.value.toMutableMap()
        val updated = current[chatId]?.map {
            if (it.id == tempId)
                it.copy(status = if (result.isSuccess) MessageStatus.SENT else MessageStatus.FAILED)
            else it
        } ?: return result.isSuccess

        current[chatId] = updated
        _messagesByChatId.value = current
        storage.saveMessages(chatId, updated)

        return result.isSuccess
    }

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
