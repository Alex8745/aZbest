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

class MeshtasticTransport(private val context: Context) {

    companion object {
        private const val TAG = "MeshtasticTransport"
        private const val MY_NODE_ID = 0x12345678 // Placeholder
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val CONNECT_RETRIES = 2
        private const val CONNECT_RETRY_DELAY_MS = 150
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L
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

    private var shouldAutoReconnect = false
    private var lastConnectedAddress: String? = null
    private var reconnectAttempt = 0

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<MeshtasticDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<MeshtasticDevice>> = _discoveredDevices.asStateFlow()

    private val _incomingMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val incomingMessages: StateFlow<List<ChatMessage>> = _incomingMessages.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val device = result.device ?: return
                val address = device.address ?: return
                if (address.isBlank()) return
                if (!isLikelyEspMeshtastic(device, result)) return

                upsertDevice(
                    MeshtasticDevice(
                        address = address,
                        name = safeDeviceName(device),
                        rssi = result.rssi
                    )
                )
            } catch (securityException: SecurityException) {
                Log.e(TAG, "No permission to read scanned device", securityException)
            } catch (throwable: Throwable) {
                Log.e(TAG, "Unexpected scan callback error", throwable)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _connectionState.value = ConnectionState.Error("Scan failed: $errorCode")
        }
    }

    fun startScan() {
        if (!hasScanPermission()) {
            _connectionState.value = ConnectionState.Error("Нет разрешения Bluetooth для сканирования")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth LE недоступен")
            return
        }

        if (!adapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Включите Bluetooth")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
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
        } catch (securityException: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permissions for scan", securityException)
            _connectionState.value = ConnectionState.Error("Нет разрешения Bluetooth для сканирования")
        } catch (throwable: Throwable) {
            Log.e(TAG, "Unexpected startScan failure", throwable)
            _connectionState.value = ConnectionState.Error("Не удалось запустить сканирование")
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (securityException: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permissions for stopScan", securityException)
        }
    }

    fun connect(deviceAddress: String) {
        if (!hasConnectPermission()) {
            _connectionState.value = ConnectionState.Error("Нет разрешения Bluetooth для подключения")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            _connectionState.value = ConnectionState.Error("Bluetooth недоступен")
            return
        }

        if (!adapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Включите Bluetooth")
            return
        }

        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (illegalArgumentException: IllegalArgumentException) {
            Log.e(TAG, "Invalid Bluetooth address: $deviceAddress", illegalArgumentException)
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

        scope.launch {
            reconnectJob?.cancelAndJoin()
            reconnectJob = null
        }

        clearClient()
        _connectionState.value = ConnectionState.Disconnected
    }

    suspend fun sendMessage(text: String): Result<Unit> {
        val client = bleClient ?: return Result.failure(IllegalStateException("Not connected"))

        return try {
            val packet = MeshtasticPacketFactory.createTextMessage(
                text = text,
                fromNodeId = MY_NODE_ID
            )
            client.sendPacket(packet)
            Result.success(Unit)
        } catch (securityException: SecurityException) {
            Log.e(TAG, "No permission to write BLE characteristic", securityException)
            Result.failure(securityException)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            Result.failure(e)
        }
    }

    private fun connectInternal(device: BluetoothDevice) {
        clearClient()
        _connectionState.value = ConnectionState.Connecting

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
                    observeIncomingPackets(client)
                    monitorConnectionLoss(client)
                }
                .fail { _, status ->
                    Log.e(TAG, "Connection failed with status: $status")
                    _connectionState.value = ConnectionState.Error("Connection failed: $status")
                    scheduleReconnect("connect fail status=$status")
                }
                .enqueue()
        } catch (securityException: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permissions for connect", securityException)
            _connectionState.value = ConnectionState.Error("Нет разрешения Bluetooth для подключения")
        } catch (throwable: Throwable) {
            Log.e(TAG, "Unexpected connect exception", throwable)
            _connectionState.value = ConnectionState.Error("Ошибка подключения")
            scheduleReconnect("connect exception")
        }
    }

    private fun observeIncomingPackets(client: MeshtasticBleClient) {
        incomingJob?.cancel()
        incomingJob = scope.launch {
            client.incomingPackets.collect { packet ->
                val text = MeshtasticPacketFactory.extractTextFromPacket(packet) ?: return@collect
                val message = ChatMessage(
                    id = packet.id.toString(),
                    text = text,
                    sender = formatNodeId(packet.from),
                    timestamp = System.currentTimeMillis(),
                    isMine = false,
                    status = MessageStatus.SENT
                )

                val current = _incomingMessages.value.toMutableList()
                current.add(message)
                _incomingMessages.value = current
            }
        }
    }

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

    private fun scheduleReconnect(reason: String) {
        if (!shouldAutoReconnect) return
        val address = lastConnectedAddress ?: return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Reconnect limit reached after reason: $reason")
            _connectionState.value = ConnectionState.Error("Не удалось переподключиться")
            return
        }

        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            reconnectAttempt += 1
            Log.d(TAG, "Reconnect attempt #$reconnectAttempt due to: $reason")
            delay(RECONNECT_DELAY_MS * reconnectAttempt)

            val adapter = bluetoothAdapter
            if (!shouldAutoReconnect || adapter == null || !adapter.isEnabled) {
                _connectionState.value = ConnectionState.Error("Bluetooth недоступен для переподключения")
                return@launch
            }

            val device = try {
                adapter.getRemoteDevice(address)
            } catch (ex: IllegalArgumentException) {
                Log.e(TAG, "Reconnect failed, invalid address: $address", ex)
                _connectionState.value = ConnectionState.Error("Некорректный адрес устройства")
                return@launch
            }

            connectInternal(device)
        }
    }

    private fun clearClient() {
        incomingJob?.cancel()
        incomingJob = null

        connectionMonitorJob?.cancel()
        connectionMonitorJob = null

        try {
            bleClient?.disconnect()?.enqueue()
        } catch (securityException: SecurityException) {
            Log.e(TAG, "No permission to disconnect BLE", securityException)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Unexpected BLE disconnect error", throwable)
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
                ?.asSequence()
                ?.filter { device -> isLikelyEspMeshtastic(device, null) }
                ?.forEach { device ->
                    val address = device.address ?: return@forEach
                    if (address.isBlank()) return@forEach
                    upsertDevice(
                        MeshtasticDevice(
                            address = address,
                            name = safeDeviceName(device),
                            rssi = Int.MIN_VALUE
                        )
                    )
                }
        } catch (securityException: SecurityException) {
            Log.e(TAG, "No permission to read bonded devices", securityException)
        }
    }

    private fun upsertDevice(device: MeshtasticDevice) {
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.address == device.address }
        if (index >= 0) {
            current[index] = device
        } else {
            current.add(device)
        }
        _discoveredDevices.value = current.sortedByDescending { it.rssi }
    }

    private fun isLikelyEspMeshtastic(device: BluetoothDevice, result: ScanResult?): Boolean {
        val name = safeDeviceName(device).lowercase()
        if (
            name.contains("meshtastic") ||
            name.contains("heltec") ||
            name.contains("esp32") ||
            name.contains("t-beam") ||
            name.contains("lora")
        ) {
            return true
        }

        return result
            ?.scanRecord
            ?.serviceUuids
            ?.any { it?.uuid == MeshtasticBleClient.SERVICE_UUID } == true
    }

    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name?.ifBlank { "Unknown" } ?: "Unknown"
        } catch (securityException: SecurityException) {
            Log.e(TAG, "No permission to read device name", securityException)
            "Unknown"
        }
    }

    private fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun formatNodeId(nodeId: Int): String {
        return "!${nodeId.toString(16).uppercase().takeLast(8)}"
    }
}