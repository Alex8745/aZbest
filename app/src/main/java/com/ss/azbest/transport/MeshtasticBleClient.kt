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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import java.util.UUID

class MeshtasticBleClient(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "MeshtasticBle"

        // Official Meshtastic UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
    }

    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null
    private var fromNumChar: BluetoothGattCharacteristic? = null

    // Поток входящих пакетов
    private val _incomingPackets = MutableSharedFlow<MeshProtos.MeshPacket>()
    val incomingPackets: SharedFlow<MeshProtos.MeshPacket> = _incomingPackets.asSharedFlow()

    // Поток состояния соединения
    private val _connectionState = MutableSharedFlow<Boolean>()
    val connectionState: SharedFlow<Boolean> = _connectionState.asSharedFlow()

    // CoroutineScope для BLE операций
    private val bleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun getGattCallback(): BleManagerGattCallback = GattCallback()

    suspend fun sendPacket(packet: MeshProtos.MeshPacket) {
        toRadioChar?.let { char ->
            val data = packet.toByteArray()
            writeCharacteristic(char, data)
                .with { _, bytes ->
                    Log.d(TAG, "Sent ${bytes.size()} bytes to radio")
                }
                .enqueue()
        }
    }

    private inner class GattCallback : BleManagerGattCallback() {

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(SERVICE_UUID) ?: return false

            toRadioChar = service.getCharacteristic(TORADIO_UUID)
            fromRadioChar = service.getCharacteristic(FROMRADIO_UUID)
            fromNumChar = service.getCharacteristic(FROMNUM_UUID)

            return toRadioChar != null && fromRadioChar != null
        }

        override fun initialize() {
            requestMtu(512).enqueue()

            fromRadioChar?.let { char ->
                setNotificationCallback(char).with { _, data ->
                    handleIncomingData(data)
                }
                enableNotifications(char).enqueue()
            }

            fromNumChar?.let { char ->
                enableNotifications(char).enqueue()
            }

            // Сообщаем, что подключение установлено
            bleScope.launch {
                _connectionState.emit(true)
            }
        }

        override fun onServicesInvalidated() {
            toRadioChar = null
            fromRadioChar = null
            fromNumChar = null

            // Сообщаем, что подключение потеряно
            bleScope.launch {
                _connectionState.emit(false)
            }
        }
    }

    private fun handleIncomingData(data: Data) {
        val bytes = data.value ?: return

        try {
            val packet = MeshProtos.MeshPacket.parseFrom(bytes)
            Log.d(TAG, "Received packet from ${packet.from}")

            bleScope.launch {
                _incomingPackets.emit(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming packet", e)
        }
    }

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }
}
