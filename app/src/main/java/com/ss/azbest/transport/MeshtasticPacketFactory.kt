package com.ss.azbest.transport

import com.google.protobuf.ByteString
import com.meshtastic.proto.MeshProtos
import kotlin.random.Random

/**
 * Фабрика для создания Meshtastic-пакетов.
 *
 * Все методы create* возвращают уже упакованные в ToRadio байты —
 * именно они записываются в характеристику TORADIO BLE.
 */
object MeshtasticPacketFactory {

    private const val BROADCAST_ADDR = 0xFFFFFFFF.toInt() // broadcast = все ноды

    // ── Создание пакетов ──────────────────────────────────────────────────────

    /**
     * Создать текстовое сообщение и обернуть в ToRadio.
     *
     * @param text       текст сообщения (UTF-8, до ~200 байт)
     * @param fromNodeId наш node ID (получаем из FromRadio.my_info при handshake)
     * @param toNodeId   получатель (по умолчанию broadcast = все)
     * @param wantAck    запрашивать подтверждение доставки
     */
    fun createTextMessageToRadio(
        text: String,
        fromNodeId: Int,
        toNodeId: Int = BROADCAST_ADDR,
        wantAck: Boolean = false
    ): ByteArray {
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(MeshProtos.PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFromUtf8(text))
            .build()

        val packet = MeshProtos.MeshPacket.newBuilder()
            .setFrom(fromNodeId)
            .setTo(toNodeId)
            .setDecoded(data)
            .setId(generatePacketId())
            .setWantAck(wantAck)
            .setPriority(MeshProtos.Priority.DEFAULT)
            .setHopLimit(3)
            .setChannel(0)
            .build()

        return MeshProtos.ToRadio.newBuilder()
            .setPacket(packet)
            .build()
            .toByteArray()
    }

    /**
     * Создать MeshPacket (без ToRadio-обёртки).
     * Используется внутри Transport, если нужен сам объект пакета.
     */
    fun createTextMeshPacket(
        text: String,
        fromNodeId: Int,
        toNodeId: Int = BROADCAST_ADDR,
        wantAck: Boolean = false
    ): MeshProtos.MeshPacket {
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(MeshProtos.PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFromUtf8(text))
            .build()

        return MeshProtos.MeshPacket.newBuilder()
            .setFrom(fromNodeId)
            .setTo(toNodeId)
            .setDecoded(data)
            .setId(generatePacketId())
            .setWantAck(wantAck)
            .setPriority(MeshProtos.Priority.DEFAULT)
            .setHopLimit(3)
            .setChannel(0)
            .build()
    }

    // ── Разбор входящих пакетов ────────────────────────────────────────────────

    /**
     * Извлечь текст из входящего MeshPacket.
     * Возвращает null, если пакет не является текстовым сообщением.
     */
    fun extractTextFromPacket(packet: MeshProtos.MeshPacket): String? {
        if (!packet.hasDecoded()) return null
        val data = packet.decoded
        if (data.portnum != MeshProtos.PortNum.TEXT_MESSAGE_APP) return null
        return data.payload.toStringUtf8().takeIf { it.isNotBlank() }
    }

    /**
     * Форматировать node ID в стандартный Meshtastic-формат: !aabbccdd
     */
    fun formatNodeId(nodeId: Int): String =
        "!${nodeId.toUInt().toString(16).padStart(8, '0')}"

    // ── Приватные утилиты ──────────────────────────────────────────────────────

    private fun generatePacketId(): Int = Random.nextInt(1, Int.MAX_VALUE)
}
