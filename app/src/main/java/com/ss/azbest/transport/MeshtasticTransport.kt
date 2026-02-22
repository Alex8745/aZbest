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
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var bleClient: MeshtasticBleClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var scanTimeoutJob: Job? = null

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

                    val name = safeDeviceName(device)
                    val discovered = MeshtasticDevice(
                        address = address,
                        name = name,
                        rssi = result.rssi
                    )
                    upsertDevice(discovered)
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

                val filter = ScanFilter.Builder().build()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

                scanner.startScan(listOf(filter), settings, scanCallback)
                scheduleScanTimeout()
            } catch (securityException: SecurityException) {
                Log.e(TAG, "Missing Bluetooth permissions for scan", securityException)
                _connectionState.value = ConnectionState.Error("Нет разрешения Bluetooth для сканирования")
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

                try {
                    stopScan()

                        val adapter = bluetoothAdapter
                        if (adapter == null) {
                            _connectionState.value = ConnectionState.Error("Bluetooth недоступен")
                            return
                        }

                        if (!adapter.isEnabled) {
                            _connectionState.value = ConnectionState.Error("Включите Bluetooth")
                            return
                        }

                        val device = adapter.getRemoteDevice(deviceAddress)
                        _connectionState.value = ConnectionState.Connecting

                        bleClient = MeshtasticBleClient(context).apply {
                            connect(device)
                                .useAutoConnect(false)
                                .timeout(10000)
                                .retry(2, 150)
                                .done {
                                    try {
                                        val deviceName = safeDeviceName(device)
                                        Log.d(TAG, "Connected to $deviceName")
                                        _connectionState.value = ConnectionState.Connected(deviceName)
                                        observeIncomingPackets()
                                    } catch (throwable: Throwable) {
                                        Log.e(TAG, "Error in connect done callback", throwable)
                                        _connectionState.value = ConnectionState.Error("Connected, but post-init failed")
                                    }
                                }
                                .fail { _, status ->
                                    Log.e(TAG, "Connection failed: $status")
                                    _connectionState.value = ConnectionState.Error("Connection failed: $status")
                                }
                                .enqueue()
                        }
                    } catch (illegalArgumentException: IllegalArgumentException) {
                        Log.e(TAG, "Invalid Bluetooth address: $deviceAddress", illegalArgumentException)
                        _connectionState.value = ConnectionState.Error("Некорректный адрес устройства")
                    } catch (securityException: SecurityException) {
                        Log.e(TAG, "Missing Bluetooth permissions for connect", securityException)
                        _connectionState.value = ConnectionState.Error("Нет разрешения Bluetooth для подключения")
                    }
                }

                fun disconnect() {
                    try {
                        bleClient?.disconnect()?.enqueue()
                    } catch (securityException: SecurityException) {
                        Log.e(TAG, "No permission to disconnect BLE", securityException)
                    }
                    bleClient = null
                    _connectionState.value = ConnectionState.Disconnected
                }

                private fun observeIncomingPackets() {
                    scope.launch {
                        bleClient?.incomingPackets?.collect { packet ->
                            val text = MeshtasticPacketFactory.extractTextFromPacket(packet)
                            if (text != null) {
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
                }

                suspend fun sendMessage(text: String): Result<Unit> {
                    val client = bleClient ?: return Result.failure(Exception("Not connected"))

                    return try {
                        val packet = MeshtasticPacketFactory.createTextMessage(
                            text = text,
                            fromNodeId = MY_NODE_ID
                        )

                        client.sendPacket(packet)
                        Result.success(Unit)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send message", e)
                        Result.failure(e)
                    }
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

                    val hasMeshtasticService = result
                        ?.scanRecord
                        ?.serviceUuids
                        ?.any { it?.uuid == MeshtasticBleClient.SERVICE_UUID } == true

                    return hasMeshtasticService
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