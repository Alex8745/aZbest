package com.ss.azbest.transport

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.meshtastic.proto.MeshProtos.FromRadio
import com.ss.azbest.domain.MeshNodeInfo
import com.meshtastic.proto.MeshProtos.MeshPacket
import com.meshtastic.proto.MeshProtos.ToRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import no.nordicsemi.android.ble.BleManager
import java.util.UUID
import kotlin.coroutines.resume

class MeshtasticBleClient(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "MeshtasticBleClient"

        val SERVICE_UUID: UUID   = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TORADIO_UUID: UUID   = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROMNUM_UUID: UUID   = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
    }

    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null
    private var fromNumChar: BluetoothGattCharacteristic? = null

    private val _incomingPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 32)
    val incomingPackets: SharedFlow<MeshPacket> = _incomingPackets.asSharedFlow()

    private val _myNodeNum = MutableStateFlow(0)
    val myNodeNum: StateFlow<Int> = _myNodeNum.asStateFlow()

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val _knownNodes = MutableStateFlow<List<MeshNodeInfo>>(emptyList())
    val knownNodes: StateFlow<List<MeshNodeInfo>> = _knownNodes.asStateFlow()

    private val bleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── API ───────────────────────────────────────────────────────────────────

    suspend fun sendPacket(packet: MeshPacket) {
        val char = toRadioChar ?: run {
            Log.w(TAG, "sendPacket: toRadio not available")
            return
        }

        val bytes = ToRadio.newBuilder()
            .setPacket(packet)
            .build()
            .toByteArray()

        // Фикс 1: определяем тип записи который реально поддерживает ESP
        // Meshtastic ESP обычно поддерживает WRITE_WITHOUT_RESPONSE — он быстрее
        val writeType =
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

        writeCharacteristic(char, bytes, writeType)
            .with { _, data -> Log.d(TAG, "Sent ${data.size()} bytes (type=$writeType)") }
            .fail { _, status -> Log.e(TAG, "Send failed, status=$status") }
            // Фикс 2: после отправки вручную дрейним FROMRADIO
            // ESP кладёт ACK сразу, но FROMNUM-уведомление может не прийти
            .done { bleScope.launch { drainFromRadio() } }
            .enqueue()
    }

    fun sendWantConfig(configId: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()) {
        val char = toRadioChar ?: run {
            Log.w(TAG, "sendWantConfig: toRadio not available")
            return
        }
        val writeType =
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

        val bytes = ToRadio.newBuilder().setWantConfigId(configId).build().toByteArray()

        writeCharacteristic(char, bytes, writeType)
            .with { _, _ -> Log.d(TAG, "Sent want_config id=$configId") }
            .fail { _, status -> Log.e(TAG, "want_config failed, status=$status") }
            // После want_config тоже дрейним — ESP сразу начнёт слать my_info, node_info
            .done { bleScope.launch { drainFromRadio() } }
            .enqueue()
    }

    // ── GATT ─────────────────────────────────────────────────────────────────

    override fun getGattCallback(): BleManagerGattCallback = GattCallback()

    private inner class GattCallback : BleManagerGattCallback() {

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(SERVICE_UUID) ?: run {
                Log.e(TAG, "Meshtastic service not found")
                return false
            }
            toRadioChar   = service.getCharacteristic(TORADIO_UUID)
            fromRadioChar = service.getCharacteristic(FROMRADIO_UUID)
            fromNumChar   = service.getCharacteristic(FROMNUM_UUID)
            return toRadioChar != null && fromRadioChar != null
        }

        override fun initialize() {
            // Подписываемся на FROMNUM сразу
            fromNumChar?.let { char ->
                setNotificationCallback(char).with { _, _ ->
                    bleScope.launch { drainFromRadio() }
                }
                enableNotifications(char).enqueue()
            }

            // Фикс 3: want_config отправляем ТОЛЬКО после успешного согласования MTU
            // Иначе большие ответы ESP обрезаются и не парсятся
            requestMtu(512)
                .with { _, mtu -> Log.d(TAG, "MTU negotiated: $mtu") }
                .done {
                    bleScope.launch { _connectionState.emit(true) }
                    sendWantConfig()
                    Log.d(TAG, "Initialized, want_config sent")
                }
                .fail { _, status ->
                    // MTU не согласован — всё равно подключаемся, но с меньшим MTU
                    Log.w(TAG, "MTU request failed (status=$status), connecting anyway")
                    bleScope.launch { _connectionState.emit(true) }
                    sendWantConfig()
                }
                .enqueue()
        }

        override fun onServicesInvalidated() {
            toRadioChar   = null
            fromRadioChar = null
            fromNumChar   = null
            bleScope.launch { _connectionState.emit(false) }
        }
    }

    // ── Приём пакетов ─────────────────────────────────────────────────────────

    private suspend fun readFromRadioOnce(): ByteArray? {
        val char = fromRadioChar ?: return null
        return suspendCancellableCoroutine { cont ->
            readCharacteristic(char)
                .with { _, data -> cont.resume(data.value ?: ByteArray(0)) }
                .fail { _, status ->
                    Log.w(TAG, "readFromRadio failed, status=$status")
                    cont.resume(null)
                }
                .enqueue()
        }
    }

    private suspend fun drainFromRadio() {
        var count = 0
        while (count++ < 50) {
            val bytes = readFromRadioOnce() ?: break
            if (bytes.isEmpty()) break
            handleFromRadioBytes(bytes)
        }
    }

    private fun handleFromRadioBytes(bytes: ByteArray) {
        val fromRadio = try {
            FromRadio.parseFrom(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse FromRadio (${bytes.size} bytes)", e)
            return
        }

        when (fromRadio.payloadVariantCase) {

            FromRadio.PayloadVariantCase.PACKET -> {
                val packet = fromRadio.packet
                Log.d(TAG, "RX from=0x${packet.from.toString(16)} " +
                    "port=${if (packet.hasDecoded()) packet.decoded.portnum else "encrypted"}")
                bleScope.launch { _incomingPackets.emit(packet) }
            }

            FromRadio.PayloadVariantCase.MY_INFO -> {
                val nodeNum = fromRadio.myInfo.myNodeNum
                Log.i(TAG, "my_info nodeNum=0x${nodeNum.toString(16)}")
                _myNodeNum.value = nodeNum
            }

            FromRadio.PayloadVariantCase.NODE_INFO -> {
                val node = fromRadio.nodeInfo
                val nodeInfo = MeshNodeInfo(
                    nodeNum = node.num,
                    nodeId = "!${Integer.toUnsignedString(node.num, 16).padStart(8, '0')}",
                    longName = node.user.longName.ifEmpty { "Unknown" },
                    shortName = node.user.shortName.ifEmpty { "?" },
                    snr = node.snr,
                    lastHeard = System.currentTimeMillis()
                )
                val current = _knownNodes.value.toMutableList()
                val idx = current.indexOfFirst { it.nodeNum == node.num }
                if (idx >= 0) current[idx] = nodeInfo else current.add(nodeInfo)
                _knownNodes.value = current
                Log.d(TAG, "node_info saved: ${nodeInfo.nodeId} \"${nodeInfo.longName}\"")
            }

            FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID ->
                Log.i(TAG, "Handshake complete — ready!")

            FromRadio.PayloadVariantCase.REBOOTED -> {
                Log.w(TAG, "ESP rebooted, re-sending want_config")
                sendWantConfig()
            }

            else -> Log.v(TAG, "Ignoring: ${fromRadio.payloadVariantCase}")
        }
    }

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }
}
