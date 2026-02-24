package com.ss.azbest.domain

data class MeshNodeInfo(
    val nodeNum: Int,
    val nodeId: String,      // "!xxxxxxxx"
    val longName: String,
    val shortName: String,
    val snr: Float,
    val lastHeard: Long      // System.currentTimeMillis() когда последний раз слышали
)
