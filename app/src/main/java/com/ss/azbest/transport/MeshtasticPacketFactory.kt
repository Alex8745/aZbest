package com.ss.azbest.transport

import com.google.protobuf.ByteString
import com.meshtastic.proto.MeshProtos.Data
import com.meshtastic.proto.MeshProtos.FromRadio
import com.meshtastic.proto.MeshProtos.Heartbeat
import com.meshtastic.proto.MeshProtos.MeshPacket
import com.meshtastic.proto.MeshProtos.MeshPacket.Priority
import com.meshtastic.proto.MeshProtos.MyNodeInfo
import com.meshtastic.proto.MeshProtos.ToRadio
import com.meshtastic.proto.Portnums.PortNum
import kotlin.random.Random

object MeshtasticPacketFactory {

    private const val BROADCAST_ADDR = 0xFFFFFFFF.toInt()

    // ── Создание пакетов ──────────────────────────────────────────────────────

    /**
     * Создать MeshPacket с текстовым сообщением.
     * Используется в Transport перед отправкой через sendPacket().
     */
    fun createTextMeshPacket(
        text: String,
        fromNodeId: Int,
        toNodeId: Int = BROADCAST_ADDR,
        wantAck: Boolean = false
    ): MeshPacket {
        val data = Data.newBuilder()
            .setPortnum(PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFromUtf8(text))
            .build()

        return MeshPacket.newBuilder()
            .setFrom(fromNodeId)
            .setTo(toNodeId)
            .setDecoded(data)
            .setId(generatePacketId())
            .setWantAck(wantAck)
            .setPriority(Priority.DEFAULT)
            .setHopLimit(3)
            .setChannel(0)
            .build()
    }

    /**
     * Создать байты ToRadio с текстовым сообщением.
     * Это то что записывается в BLE-характеристику TORADIO.
     */
    fun createTextMessageToRadio(
        text: String,
        fromNodeId: Int,
        toNodeId: Int = BROADCAST_ADDR,
        wantAck: Boolean = false
    ): ByteArray {
        return ToRadio.newBuilder()
            .setPacket(createTextMeshPacket(text, fromNodeId, toNodeId, wantAck))
            .build()
            .toByteArray()
    }

    /**
     * Создать ToRadio want_config — запрос конфигурации при подключении.
     */
    fun createWantConfig(configId: Int): ByteArray {
        return ToRadio.newBuilder()
            .setWantConfigId(configId)
            .build()
            .toByteArray()
    }

    /**
     * Создать ToRadio heartbeat — keepalive пинг.
     */
    fun createHeartbeat(): ByteArray {
        return ToRadio.newBuilder()
            .setHeartbeat(Heartbeat.getDefaultInstance())
            .build()
            .toByteArray()
    }

    // ── Разбор входящих пакетов ────────────────────────────────────────────────

    /**
     * Извлечь текст из входящего MeshPacket.
     * Возвращает null если пакет не TEXT_MESSAGE_APP.
     */
    fun extractTextFromPacket(packet: MeshPacket): String? {
        if (!packet.hasDecoded()) return null
        val data = packet.decoded
        if (data.portnum != PortNum.TEXT_MESSAGE_APP) return null
        return data.payload.toStringUtf8().takeIf { it.isNotBlank() }
    }

    /**
     * Форматировать node ID в стандартный Meshtastic-формат: !aabbccdd
     */
    fun formatNodeId(nodeId: Int): String =
        "!${Integer.toUnsignedString(nodeId, 16).padStart(8, '0')}"

    // ── Утилиты ────────────────────────────────────────────────────────────────

    private fun generatePacketId(): Int = Random.nextInt(1, Int.MAX_VALUE)
}
