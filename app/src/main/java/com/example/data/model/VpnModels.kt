package com.example.data.model

import com.example.data.local.HopChainEntity
import com.example.data.local.RelayNodeEntity

data class HopChainResolved(
    val chain: HopChainEntity,
    val entryNode: RelayNodeEntity,
    val middleNode: RelayNodeEntity?,
    val exitNode: RelayNodeEntity
) {
    val hops: List<RelayNodeEntity>
        get() = listOfNotNull(entryNode, middleNode, exitNode)

    val hopCount: Int
        get() = hops.size

    val summaryLabel: String
        get() = hops.joinToString(" → ") { it.regionCode.ifBlank { it.name } }

    val combinedLatencyMs: Int?
        get() {
            val values = hops.mapNotNull { it.lastLatencyMs }
            return if (values.size == hops.size) values.sum() else null
        }
}

sealed interface TunnelState {
    data object Disconnected : TunnelState
    data class Handshaking(val stage: String, val currentHopIndex: Int, val totalHops: Int) : TunnelState
    data class Connected(
        val chainName: String,
        val hopSummary: String,
        val hopsCount: Int,
        val localTunIp: String,
        val isRelayVerified: Boolean,
        val statusNote: String
    ) : TunnelState
    data class Error(val message: String) : TunnelState
}

data class PacketTelemetryItem(
    val id: Long,
    val timestampMillis: Long,
    val protocol: String,
    val sourceEndpoint: String,
    val destinationEndpoint: String,
    val byteLength: Int,
    val dnsQuery: String? = null,
    val actionLabel: String = "TUN"
)

data class ProtocolStats(
    val tcpPackets: Long = 0L,
    val udpPackets: Long = 0L,
    val icmpPackets: Long = 0L,
    val dnsQueries: Long = 0L,
    val relayedDnsResponses: Long = 0L
)

data class ThroughputSample(
    val timestampMillis: Long,
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long
)

data class VpnTelemetrySnapshot(
    val state: TunnelState = TunnelState.Disconnected,
    val connectedSinceMillis: Long? = null,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L,
    val totalPackets: Long = 0L,
    val protocolStats: ProtocolStats = ProtocolStats(),
    val recentPackets: List<PacketTelemetryItem> = emptyList(),
    val throughputHistory: List<ThroughputSample> = emptyList(),
    val activeChainId: Long = -1L
)

data class EgressDiagnostics(
    val publicIp: String,
    val colo: String,
    val countryCode: String,
    val protocol: String,
    val roundTripMs: Long,
    val checkedAtMillis: Long = System.currentTimeMillis(),
    val error: String? = null
)

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)
