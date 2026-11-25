package com.example.vpn

import com.example.data.model.PacketTelemetryItem
import com.example.data.model.ProtocolStats
import com.example.data.model.ThroughputSample
import com.example.data.model.TunnelState
import com.example.data.model.VpnTelemetrySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

object VpnRuntimeBus {

    private val packetIdGen = AtomicLong(1L)
    private val _snapshot = MutableStateFlow(VpnTelemetrySnapshot())
    val snapshot: StateFlow<VpnTelemetrySnapshot> = _snapshot.asStateFlow()

    private var lastTickRx = 0L
    private var lastTickTx = 0L

    fun updateState(state: TunnelState, activeChainId: Long = _snapshot.value.activeChainId) {
        _snapshot.update { current ->
            val connectedSince = when (state) {
                is TunnelState.Connected -> current.connectedSinceMillis ?: System.currentTimeMillis()
                is TunnelState.Disconnected, is TunnelState.Error -> null
                is TunnelState.Handshaking -> current.connectedSinceMillis
            }
            val resetMetrics = state is TunnelState.Disconnected
            if (resetMetrics) {
                lastTickRx = 0L
                lastTickTx = 0L
            }
            current.copy(
                state = state,
                activeChainId = activeChainId,
                connectedSinceMillis = connectedSince,
                rxBytes = if (resetMetrics) 0L else current.rxBytes,
                txBytes = if (resetMetrics) 0L else current.txBytes,
                rxBytesPerSec = if (resetMetrics) 0L else current.rxBytesPerSec,
                txBytesPerSec = if (resetMetrics) 0L else current.txBytesPerSec,
                totalPackets = if (resetMetrics) 0L else current.totalPackets,
                protocolStats = if (resetMetrics) ProtocolStats() else current.protocolStats,
                recentPackets = if (resetMetrics) emptyList() else current.recentPackets,
                throughputHistory = if (resetMetrics) emptyList() else current.throughputHistory
            )
        }
    }

    fun recordPacket(
        protocol: String,
        sourceEndpoint: String,
        destinationEndpoint: String,
        txDelta: Int,
        rxDelta: Int,
        dnsQuery: String? = null,
        actionLabel: String = "TUN",
        relayedDns: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        _snapshot.update { current ->
            val stats = current.protocolStats
            val updatedStats = stats.copy(
                tcpPackets = stats.tcpPackets + if (protocol == "TCP") 1 else 0,
                udpPackets = stats.udpPackets + if (protocol == "UDP") 1 else 0,
                icmpPackets = stats.icmpPackets + if (protocol == "ICMP") 1 else 0,
                dnsQueries = stats.dnsQueries + if (dnsQuery != null) 1 else 0,
                relayedDnsResponses = stats.relayedDnsResponses + if (relayedDns) 1 else 0
            )
            val newItem = PacketTelemetryItem(
                id = packetIdGen.getAndIncrement(),
                timestampMillis = now,
                protocol = protocol,
                sourceEndpoint = sourceEndpoint,
                destinationEndpoint = destinationEndpoint,
                byteLength = txDelta + rxDelta,
                dnsQuery = dnsQuery,
                actionLabel = actionLabel
            )
            val updatedList = (listOf(newItem) + current.recentPackets).take(60)
            current.copy(
                rxBytes = current.rxBytes + rxDelta,
                txBytes = current.txBytes + txDelta,
                totalPackets = current.totalPackets + 1,
                protocolStats = updatedStats,
                recentPackets = updatedList
            )
        }
    }

    fun sampleThroughputTick() {
        _snapshot.update { current ->
            val rxDelta = (current.rxBytes - lastTickRx).coerceAtLeast(0L)
            val txDelta = (current.txBytes - lastTickTx).coerceAtLeast(0L)
            lastTickRx = current.rxBytes
            lastTickTx = current.txBytes
            val sample = ThroughputSample(
                timestampMillis = System.currentTimeMillis(),
                rxBytesPerSec = rxDelta,
                txBytesPerSec = txDelta
            )
            val history = (current.throughputHistory + sample).takeLast(30)
            current.copy(
                rxBytesPerSec = rxDelta,
                txBytesPerSec = txDelta,
                throughputHistory = history
            )
        }
    }
}
