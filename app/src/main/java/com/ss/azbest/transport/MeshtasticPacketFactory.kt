package com.ss.azbest.transport

import com.google.protobuf.ByteString
import com.meshtastic.proto.MeshProtos
import kotlin.random.Random

object MeshtasticPacketFactory {

    private const val BROADCAST_ADDR = 0xFFFFFFFF.toInt()

    fun createTextMessage(
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

    private fun generatePacketId(): Int {
        return Random.nextInt(1, Int.MAX_VALUE)
    }

    fun extractTextFromPacket(packet: MeshProtos.MeshPacket): String? {
        if (!packet.hasDecoded()) return null

        val data = packet.decoded
        if (data.portnum != MeshProtos.PortNum.TEXT_MESSAGE_APP) return null

        return data.payload.toStringUtf8()
    }

    fun getNodeIdFromPacket(packet: MeshProtos.MeshPacket): Int {
        return packet.from
    }
}