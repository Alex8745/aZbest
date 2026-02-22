package com.ss.azbest.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ss.azbest.domain.ChatMessage
import com.ss.azbest.domain.ConnectionState
import com.ss.azbest.domain.MessageStatus
import com.ss.azbest.domain.MeshtasticDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Транспортный слой: управляет BLE-соединением с Meshtastic-устройством.
 *
 * Поток работы:
 *  1. [startScan] — найти ESP-устройства поблизости
 *  2. [connect]   — подключиться к выбранному
 *  3. После подключения автоматически отправляется want_config handshake
 *  4. [myNodeNum] обновляется когда ESP присылает my_info
 *  5. [sendMessage] — отправить текстовое сообщение
 *  6. Входящие сообщения приходят в [incomingMessages]
 */
class MeshtasticTransport(private val context: Context) {

    companion object {
        private const val TAG = "MeshtasticTransport"
        private const val SCAN_TIMEOUT_MS      = 12_000L
        private const val CONNECT_TIMEOUT_MS   = 10_000L
        private const val CONNECT_RETRIES      = 2
        private const val CONNECT_RETRY_DELAY_MS = 150
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS   = 2_000L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var bleClient: MeshtasticBleClient? = null
    private var incomingJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var nodeIdObserveJob: Job? = null

    private var shouldAutoReconnect = false
    private var lastConnectedAddress: String? = null
    private var reconnectAttempt = 0

    /**
     * Node ID нашего устройства.
     * 0 пока не завершён handshake с ESP (until my_info получен).
     * Используется как поле "from" во всех исходящих пакетах.
     */
    private var myNodeNum: Int = 0

    // ── Публичные StateFlow ────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<MeshtasticDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<MeshtasticDevice>> = _discoveredDevices.asStateFlow()

    private val _incomingMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val incomingMessages: StateFlow<List<ChatMessage>> = _incomingMessages.asStateFlow()

    // ── Сканирование ──────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val device = result.device ?: return
                val address = device.address ?: return
                if (address.isBlank()) return
                if (!isLikelyMeshtastic(device, result)) return

                upsertDevice(
                    MeshtasticDevice(
                        address = address,
                        name = safeDeviceName(device),
                        rssi = result.rssi
                    )
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission error in scan callback", e)
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected scan callback error", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed, error=$errorCode")
            _connectionState.value = ConnectionState.Error("Scan failed: $errorCode")
        }
    }

    fun startScan() {
        if (!hasScanPermission()) {
            _connectionState.value = ConnectionState.Error("Нет разрешения Bluetooth для сканирования")
            return
        }
        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = ConnectionState.Error("Bluetooth LE недоступен")
            return
        }
        if (!adapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Включите Bluetooth")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            _connectionState.value = ConnectionState.Error("BLE scanner недоступен")
            return
        }

        try {
            _connectionState.value = ConnectionState.Scanning
            _discoveredDevices.value = emptyList()
            seedBondedDevices(adapter)

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(listOf(ScanFilter.Builder().build()), settings, scanCallback)
            scheduleScanTimeout()
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permissions for scan", e)
            _connectionState.value = ConnectionState.Error("Нет разрешения Bluetooth для сканирования")
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected startScan failure", e)
            _connectionState.value = ConnectionState.Error("Не удалось запустить сканирование")
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permissions for stopScan", e)
        }
    }

    // ── Подключение ────────────────────────────────────────────────────────────

    fun connect(deviceAddress: String) {
        if (!hasConnectPermission()) {
            _connectionState.value = ConnectionState.Error("Нет разрешения Bluetooth для подключения")
            return
        }
        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = ConnectionState.Error("Bluetooth недоступен")
            return
        }
        if (!adapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Включите Bluetooth")
            return
        }
        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid Bluetooth address: $deviceAddress", e)
            _connectionState.value = ConnectionState.Error("Некорректный адрес устройства")
            return
        }

        stopScan()
        shouldAutoReconnect = true
        lastConnectedAddress = deviceAddress
        reconnectAttempt = 0

        connectInternal(device)
    }

    fun disconnect() {
        shouldAutoReconnect = false
        lastConnectedAddress = null
        reconnectAttempt = 0
        myNodeNum = 0

        scope.launch {
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
        }

        clearClient()
        _connectionState.value = ConnectionState.Disconnected
    }

    // ── Отправка сообщений ─────────────────────────────────────────────────────

    suspend fun sendMessage(text: String): Result<Unit> {
        val client = bleClient
            ?: return Result.failure(IllegalStateException("Not connected"))

        if (myNodeNum == 0) {
            Log.w(TAG, "sendMessage: myNodeNum not yet received from ESP, using fallback")
        }

        return try {
            val packet = MeshtasticPacketFactory.createTextMeshPacket(
                text = text,
                fromNodeId = myNodeNum  // 0 до завершения handshake, потом реальный ID
            )
            client.sendPacket(packet)
            Log.d(TAG, "Message sent: \"$text\" from=${MeshtasticPacketFactory.formatNodeId(myNodeNum)}")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to write BLE characteristic", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            Result.failure(e)
        }
    }

    // ── Внутренняя логика подключения ─────────────────────────────────────────

    private fun connectInternal(device: BluetoothDevice) {
        clearClient()
        _connectionState.value = ConnectionState.Connecting
        myNodeNum = 0

        val client = MeshtasticBleClient(context)
        bleClient = client

        try {
            client.connect(device)
                .useAutoConnect(false)
                .timeout(CONNECT_TIMEOUT_MS)
                .retry(CONNECT_RETRIES, CONNECT_RETRY_DELAY_MS)
                .done {
                    reconnectAttempt = 0
                    val deviceName = safeDeviceName(device)
                    Log.d(TAG, "Connected to $deviceName (${device.address})")
                    _connectionState.value = ConnectionState.Connected(deviceName)

                    // ① Запустить handshake — ESP ответит my_info + node_info + config_complete_id
                    client.sendWantConfig()

                    // ② Слушать входящие пакеты
                    observeIncomingPackets(client)

                    // ③ Следить за node ID (обновится после my_info)
                    observeMyNodeNum(client)

                    // ④ Следить за потерей соединения
                    monitorConnectionLoss(client)
                }
                .fail { _, status ->
                    Log.e(TAG, "Connection failed with status: $status")
                    _connectionState.value = ConnectionState.Error("Connection failed: $status")
                    scheduleReconnect("connect fail status=$status")
                }
                .enqueue()
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permissions for connect", e)
            _connectionState.value = ConnectionState.Error("Нет разрешения Bluetooth для подключения")
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected connect exception", e)
            _connectionState.value = ConnectionState.Error("Ошибка подключения")
            scheduleReconnect("connect exception")
        }
    }

    /**
     * Слушаем входящие MeshPacket и конвертируем в ChatMessage.
     * Игнорируем не-текстовые пакеты.
     */
    private fun observeIncomingPackets(client: MeshtasticBleClient) {
        incomingJob?.cancel()
        incomingJob = scope.launch {
            client.incomingPackets.collect { packet ->
                val text = MeshtasticPacketFactory.extractTextFromPacket(packet) ?: return@collect

                // Пакет от нас самих — не добавлять (может прийти эхо)
                if (packet.from == myNodeNum && myNodeNum != 0) {
                    Log.d(TAG, "Ignoring echo from self")
                    return@collect
                }

                val message = ChatMessage(
                    id = packet.id.toString(),
                    text = text,
                    sender = MeshtasticPacketFactory.formatNodeId(packet.from),
                    timestamp = if (packet.rxTime != 0) {
                        packet.rxTime.toLong() * 1000
                    } else {
                        System.currentTimeMillis()
                    },
                    isMine = false,
                    status = MessageStatus.SENT
                )

                val current = _incomingMessages.value.toMutableList()
                // Дедупликация по id
                if (current.none { it.id == message.id }) {
                    current.add(message)
                    _incomingMessages.value = current
                    Log.d(TAG, "New message from ${message.sender}: \"${message.text}\"")
                }
            }
        }
    }

    /**
     * Следим за myNodeNum в BleClient — он обновится когда ESP пришлёт my_info.
     */
    private fun observeMyNodeNum(client: MeshtasticBleClient) {
        nodeIdObserveJob?.cancel()
        nodeIdObserveJob = scope.launch {
            client.myNodeNum.collect { nodeNum ->
                if (nodeNum != 0) {
                    myNodeNum = nodeNum
                    Log.i(TAG, "Our node ID: ${MeshtasticPacketFactory.formatNodeId(nodeNum)}")
                }
            }
        }
    }

    /**
     * Следим за состоянием BLE-соединения.
     * При разрыве — планируем переподключение.
     */
    private fun monitorConnectionLoss(client: MeshtasticBleClient) {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = scope.launch {
            client.connectionState.collect { isConnected ->
                if (!isConnected && shouldAutoReconnect) {
                    Log.w(TAG, "BLE link lost, scheduling reconnect")
                    _connectionState.value = ConnectionState.Error("Соединение потеряно")
                    scheduleReconnect("link lost")
                }
            }
        }
    }

    // ── Переподключение ────────────────────────────────────────────────────────

    private fun scheduleReconnect(reason: String) {
        if (!shouldAutoReconnect) return
        val address = lastConnectedAddress ?: return

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Reconnect limit reached after: $reason")
            _connectionState.value = ConnectionState.Error("Не удалось переподключиться")
            return
        }

        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            reconnectAttempt++
            val delayMs = RECONNECT_DELAY_MS * reconnectAttempt
            Log.d(TAG, "Reconnect #$reconnectAttempt in ${delayMs}ms, reason: $reason")
            delay(delayMs)

            if (!shouldAutoReconnect) return@launch

            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                _connectionState.value = ConnectionState.Error("Bluetooth недоступен для переподключения")
                return@launch
            }

            val device = try {
                adapter.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Reconnect failed, invalid address: $address", e)
                _connectionState.value = ConnectionState.Error("Некорректный адрес устройства")
                return@launch
            }

            connectInternal(device)
        }
    }

    // ── Вспомогательные методы ─────────────────────────────────────────────────

    private fun clearClient() {
        incomingJob?.cancel();        incomingJob = null
        connectionMonitorJob?.cancel(); connectionMonitorJob = null
        nodeIdObserveJob?.cancel();   nodeIdObserveJob = null

        try {
            bleClient?.disconnect()?.enqueue()
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to disconnect BLE", e)
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected BLE disconnect error", e)
        }
        bleClient = null
    }

    private fun scheduleScanTimeout() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            stopScan()
            if (_connectionState.value is ConnectionState.Scanning) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    private fun seedBondedDevices(adapter: BluetoothAdapter) {
        if (!hasConnectPermission()) return
        try {
            adapter.bondedDevices
                ?.filter { isLikelyMeshtastic(it, null) }
                ?.forEach { device ->
                    val address = device.address?.takeIf { it.isNotBlank() } ?: return@forEach
                    upsertDevice(MeshtasticDevice(address, safeDeviceName(device), Int.MIN_VALUE))
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to read bonded devices", e)
        }
    }

    private fun upsertDevice(device: MeshtasticDevice) {
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.address == device.address }
        if (index >= 0) current[index] = device else current.add(device)
        _discoveredDevices.value = current.sortedByDescending { it.rssi }
    }

    /**
     * Эвристика: устройство похоже на Meshtastic-устройство,
     * если его имя содержит известные ключевые слова
     * или его BLE-сервис совпадает с официальным UUID Meshtastic.
     */
    private fun isLikelyMeshtastic(device: BluetoothDevice, result: ScanResult?): Boolean {
        val name = safeDeviceName(device).lowercase()
        if (name.contains("meshtastic") ||
            name.contains("heltec") ||
            name.contains("esp32") ||
            name.contains("t-beam") ||
            name.contains("lora")
        ) return true

        return result?.scanRecord?.serviceUuids
            ?.any { it?.uuid == MeshtasticBleClient.SERVICE_UUID } == true
    }

    private fun safeDeviceName(device: BluetoothDevice): String =
        try { device.name?.ifBlank { "Unknown" } ?: "Unknown" }
        catch (e: SecurityException) { "Unknown" }

    private fun hasScanPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN
        else
            Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT
        else
            Manifest.permission.BLUETOOTH
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
}
