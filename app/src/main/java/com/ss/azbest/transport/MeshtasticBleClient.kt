package com.ss.azbest.transport

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.meshtastic.proto.MeshProtos
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
import no.nordicsemi.android.ble.data.Data
import java.util.UUID
import kotlin.coroutines.resume

/**
 * BLE-клиент для Meshtastic-устройств (ESP32).
 *
 * Протокол:
 *   Телефон → ESP : байты ToRadio  → характеристика TORADIO_UUID
 *   ESP → Телефон : характеристика FROMNUM_UUID уведомляет об изменении счётчика →
 *                   телефон читает FROMRADIO_UUID в цикле пока не пустой →
 *                   каждый прочитанный блок = один FromRadio protobuf
 *
 * После подключения вызови [sendWantConfig] чтобы запустить handshake.
 * [myNodeNum] будет обновлён после получения FromRadio.my_info от ESP.
 */
class MeshtasticBleClient(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "MeshtasticBleClient"

        // Официальные UUID Meshtastic BLE-сервиса
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")

        // Телефон пишет сюда ToRadio-байты (MeshPacket, want_config, heartbeat)
        val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")

        // Телефон читает отсюда FromRadio-байты после уведомления FROMNUM
        val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")

        // Счётчик входящих пакетов — при изменении нужно читать FROMRADIO
        val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
    }

    // ── Характеристики ────────────────────────────────────────────────────────

    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null
    private var fromNumChar: BluetoothGattCharacteristic? = null

    // ── Публичные потоки ──────────────────────────────────────────────────────

    /** Входящие текстовые MeshPacket (уже распакованы из FromRadio) */
    private val _incomingPackets = MutableSharedFlow<MeshProtos.MeshPacket>(extraBufferCapacity = 32)
    val incomingPackets: SharedFlow<MeshProtos.MeshPacket> = _incomingPackets.asSharedFlow()

    /** Node ID нашего устройства (0 пока не получен my_info) */
    private val _myNodeNum = MutableStateFlow(0)
    val myNodeNum: StateFlow<Int> = _myNodeNum.asStateFlow()

    /** true = BLE-соединение активно */
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val bleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Публичный API ─────────────────────────────────────────────────────────

    /**
     * Отправить текстовый MeshPacket на ESP.
     * Пакет оборачивается в ToRadio перед записью.
     */
    suspend fun sendPacket(packet: MeshProtos.MeshPacket) {
        val char = toRadioChar ?: run {
            Log.w(TAG, "sendPacket: toRadio characteristic not available")
            return
        }
        val toRadioBytes = MeshProtos.ToRadio.newBuilder()
            .setPacket(packet)
            .build()
            .toByteArray()

        writeCharacteristic(char, toRadioBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .with { _, data -> Log.d(TAG, "Sent packet (${data.size()} bytes)") }
            .fail { _, status -> Log.e(TAG, "Failed to send packet, status=$status") }
            .enqueue()
    }

    /**
     * Отправить want_config — запрос конфигурации ESP.
     * Вызывать сразу после установки соединения (из Transport.done{}).
     * ESP ответит: my_info → node_info*N → config_complete_id
     */
    fun sendWantConfig(configId: Int = System.currentTimeMillis().toInt()) {
        val char = toRadioChar ?: run {
            Log.w(TAG, "sendWantConfig: toRadio characteristic not available")
            return
        }
        val bytes = MeshProtos.ToRadio.newBuilder()
            .setWantConfigId(configId)
            .build()
            .toByteArray()

        writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .with { _, _ -> Log.d(TAG, "Sent want_config id=$configId") }
            .fail { _, status -> Log.e(TAG, "Failed to send want_config, status=$status") }
            .enqueue()
    }

    /**
     * Отправить heartbeat — keepalive-пинг для удержания соединения.
     */
    fun sendHeartbeat() {
        val char = toRadioChar ?: return
        val bytes = MeshProtos.ToRadio.newBuilder()
            .setHeartbeat(MeshProtos.Heartbeat.getDefaultInstance())
            .build()
            .toByteArray()

        writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .enqueue()
    }

    // ── GATT callback ─────────────────────────────────────────────────────────

    override fun getGattCallback(): BleManagerGattCallback = GattCallback()

    private inner class GattCallback : BleManagerGattCallback() {

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(SERVICE_UUID) ?: run {
                Log.e(TAG, "Meshtastic BLE service not found!")
                return false
            }
            toRadioChar   = service.getCharacteristic(TORADIO_UUID)
            fromRadioChar = service.getCharacteristic(FROMRADIO_UUID)
            fromNumChar   = service.getCharacteristic(FROMNUM_UUID)

            val ok = toRadioChar != null && fromRadioChar != null
            if (!ok) Log.e(TAG, "Required characteristics missing. toRadio=$toRadioChar fromRadio=$fromRadioChar")
            return ok
        }

        override fun initialize() {
            // Запросить максимальный MTU для больших пакетов
            requestMtu(512).enqueue()

            // Подписаться на FROMNUM — уведомление означает: «в FROMRADIO есть новые байты»
            fromNumChar?.let { char ->
                setNotificationCallback(char).with { _, _ ->
                    // На каждое уведомление FROMNUM — читаем FROMRADIO в цикле
                    bleScope.launch { drainFromRadio() }
                }
                enableNotifications(char).enqueue()
            }

            // Сигнализируем: соединение готово
            bleScope.launch { _connectionState.emit(true) }

            Log.d(TAG, "BLE initialized, characteristics ready")
        }

        override fun onServicesInvalidated() {
            toRadioChar   = null
            fromRadioChar = null
            fromNumChar   = null
            bleScope.launch { _connectionState.emit(false) }
            Log.d(TAG, "BLE services invalidated (disconnected)")
        }
    }

    // ── Приём пакетов ─────────────────────────────────────────────────────────

    /**
     * Прочитать одну порцию байт из FROMRADIO.
     * Возвращает null при ошибке, пустой массив = очередь исчерпана.
     */
    private suspend fun readFromRadioOnce(): ByteArray? {
        val char = fromRadioChar ?: return null
        return suspendCancellableCoroutine { cont ->
            readCharacteristic(char)
                .with { _, data ->
                    cont.resume(data.value ?: ByteArray(0))
                }
                .fail { _, status ->
                    Log.w(TAG, "readFromRadio failed, status=$status")
                    cont.resume(null)
                }
                .enqueue()
        }
    }

    /**
     * Drain-loop: читаем FROMRADIO до тех пор, пока не получим пустой ответ.
     * Каждый непустой блок байт = один FromRadio protobuf.
     *
     * Вызывается из coroutine при каждом FROMNUM-уведомлении.
     */
    private suspend fun drainFromRadio() {
        var attempts = 0
        val maxAttempts = 50 // защита от бесконечного цикла

        while (attempts < maxAttempts) {
            attempts++
            val bytes = readFromRadioOnce() ?: break  // null = ошибка чтения
            if (bytes.isEmpty()) break                  // пусто = очередь исчерпана
            handleFromRadioBytes(bytes)
        }
    }

    /**
     * Разбираем FromRadio и диспетчеризуем по типу.
     */
    private fun handleFromRadioBytes(bytes: ByteArray) {
        val fromRadio = try {
            MeshProtos.FromRadio.parseFrom(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse FromRadio (${bytes.size} bytes)", e)
            return
        }

        Log.d(TAG, "FromRadio variant: ${fromRadio.payloadVariantCase}")

        when (fromRadio.payloadVariantCase) {

            MeshProtos.FromRadio.PayloadVariantCase.PACKET -> {
                // Входящий mesh-пакет (сообщение, позиция и т.д.)
                val packet = fromRadio.packet
                Log.d(TAG, "Packet from=0x${packet.from.toString(16)} portnum=${
                    if (packet.hasDecoded()) packet.decoded.portnum else "encrypted"
                }")
                bleScope.launch { _incomingPackets.emit(packet) }
            }

            MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> {
                // Наш node ID — первое что приходит при handshake
                val nodeNum = fromRadio.myInfo.myNodeNum
                Log.d(TAG, "Received my_info: myNodeNum=0x${nodeNum.toString(16)}")
                _myNodeNum.value = nodeNum
            }

            MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> {
                // Информация об известных нодах сети (может прийти много)
                val node = fromRadio.nodeInfo
                Log.d(TAG, "NodeInfo: num=0x${node.num.toString(16)} name=${node.user.longName}")
            }

            MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> {
                // Handshake завершён — ESP передал всю начальную конфигурацию
                Log.d(TAG, "Config complete, id=${fromRadio.configCompleteId}. Ready to communicate!")
            }

            MeshProtos.FromRadio.PayloadVariantCase.REBOOTED -> {
                Log.w(TAG, "ESP rebooted, re-sending want_config")
                // Устройство перезагрузилось — нужно повторить handshake
                sendWantConfig()
            }

            MeshProtos.FromRadio.PayloadVariantCase.QUEUE_STATUS -> {
                val qs = fromRadio.queueStatus
                Log.d(TAG, "QueueStatus: res=${qs.res} free=${qs.free}/${qs.maxlen}")
            }

            else -> {
                Log.v(TAG, "Ignoring FromRadio variant: ${fromRadio.payloadVariantCase}")
            }
        }
    }

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }
}
