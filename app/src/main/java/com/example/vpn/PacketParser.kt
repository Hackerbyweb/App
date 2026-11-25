package com.example.vpn

import java.net.InetAddress

data class ParsedIpv4Packet(
    val version: Int,
    val ihlBytes: Int,
    val totalLength: Int,
    val protocolNumber: Int,
    val protocolName: String,
    val srcIp: String,
    val dstIp: String,
    val srcIpBytes: ByteArray,
    val dstIpBytes: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
    val dnsQueryName: String?
)

object PacketParser {

    fun parseIpv4(buffer: ByteArray, length: Int): ParsedIpv4Packet? {
        if (length < 20) return null
        val version = (buffer[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val ihlBytes = (buffer[0].toInt() and 0x0F) * 4
        if (ihlBytes < 20 || length < ihlBytes) return null

        val totalLength = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        val effectiveLen = minOf(length, totalLength)
        val protocolNumber = buffer[9].toInt() and 0xFF
        val protocolName = when (protocolNumber) {
            1 -> "ICMP"
            6 -> "TCP"
            17 -> "UDP"
            else -> "IP-$protocolNumber"
        }

        val srcIpBytes = buffer.copyOfRange(12, 16)
        val dstIpBytes = buffer.copyOfRange(16, 20)
        val srcIp = InetAddress.getByAddress(srcIpBytes).hostAddress ?: "0.0.0.0"
        val dstIp = InetAddress.getByAddress(dstIpBytes).hostAddress ?: "0.0.0.0"

        var srcPort = 0
        var dstPort = 0
        var payloadOffset = ihlBytes
        var payloadLength = (effectiveLen - ihlBytes).coerceAtLeast(0)
        var dnsQuery: String? = null

        if (protocolNumber == 6 && effectiveLen >= ihlBytes + 20) {
            srcPort = ((buffer[ihlBytes].toInt() and 0xFF) shl 8) or (buffer[ihlBytes + 1].toInt() and 0xFF)
            dstPort = ((buffer[ihlBytes + 2].toInt() and 0xFF) shl 8) or (buffer[ihlBytes + 3].toInt() and 0xFF)
            val tcpHeaderLen = ((buffer[ihlBytes + 12].toInt() ushr 4) and 0x0F) * 4
            payloadOffset = (ihlBytes + tcpHeaderLen).coerceAtMost(effectiveLen)
            payloadLength = (effectiveLen - payloadOffset).coerceAtLeast(0)
        } else if (protocolNumber == 17 && effectiveLen >= ihlBytes + 8) {
            srcPort = ((buffer[ihlBytes].toInt() and 0xFF) shl 8) or (buffer[ihlBytes + 1].toInt() and 0xFF)
            dstPort = ((buffer[ihlBytes + 2].toInt() and 0xFF) shl 8) or (buffer[ihlBytes + 3].toInt() and 0xFF)
            payloadOffset = ihlBytes + 8
            payloadLength = (effectiveLen - payloadOffset).coerceAtLeast(0)
            if (dstPort == 53 && payloadLength > 12) {
                dnsQuery = extractDnsQName(buffer, payloadOffset, effectiveLen)
            }
        }

        return ParsedIpv4Packet(
            version = version,
            ihlBytes = ihlBytes,
            totalLength = effectiveLen,
            protocolNumber = protocolNumber,
            protocolName = protocolName,
            srcIp = srcIp,
            dstIp = dstIp,
            srcIpBytes = srcIpBytes,
            dstIpBytes = dstIpBytes,
            srcPort = srcPort,
            dstPort = dstPort,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            dnsQueryName = dnsQuery
        )
    }

    private fun extractDnsQName(buffer: ByteArray, dnsStart: Int, limit: Int): String? {
        return runCatching {
            var idx = dnsStart + 12 // skip 12-byte DNS header
            val labels = mutableListOf<String>()
            while (idx < limit) {
                val len = buffer[idx].toInt() and 0xFF
                if (len == 0) break
                if ((len and 0xC0) == 0xC0) break
                idx++
                if (idx + len > limit) break
                labels.add(String(buffer, idx, len, Charsets.US_ASCII))
                idx += len
            }
            labels.takeIf { it.isNotEmpty() }?.joinToString(".")
        }.getOrNull()
    }

    /**
     * Builds a valid IPv4 + UDP response packet (e.g., to inject relayed DNS responses
     * back into the Android TUN interface).
     */
    fun buildIpv4UdpResponse(
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        srcPort: Int,
        dstPort: Int,
        udpPayload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val udpLen = udpHeaderLen + udpPayload.size
        val totalLen = ipHeaderLen + udpLen
        val packet = ByteArray(totalLen)

        // IPv4 Header
        packet[0] = 0x45.toByte()
        packet[1] = 0x00
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00
        packet[5] = 0x01
        packet[6] = 0x40.toByte() // Don't Fragment
        packet[7] = 0x00
        packet[8] = 64.toByte() // TTL
        packet[9] = 17.toByte() // UDP
        // Checksum [10..11] filled below
        System.arraycopy(srcIpBytes, 0, packet, 12, 4)
        System.arraycopy(dstIpBytes, 0, packet, 16, 4)

        val ipChecksum = onesComplementChecksum(packet, 0, ipHeaderLen)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header
        packet[20] = ((srcPort ushr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort ushr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = ((udpLen ushr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        // UDP checksum [26..27] computed over pseudo-header
        System.arraycopy(udpPayload, 0, packet, 28, udpPayload.size)

        val udpChecksum = computeUdpChecksum(srcIpBytes, dstIpBytes, packet, 20, udpLen)
        packet[26] = ((udpChecksum ushr 8) and 0xFF).toByte()
        packet[27] = (udpChecksum and 0xFF).toByte()

        return packet
    }

    private fun onesComplementChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun computeUdpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        packet: ByteArray,
        udpOffset: Int,
        udpLen: Int
    ): Int {
        var sum = 0
        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 17 // UDP protocol
        sum += udpLen

        var i = udpOffset
        val end = udpOffset + udpLen
        while (i < end - 1) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val result = sum.inv() and 0xFFFF
        return if (result == 0) 0xFFFF else result
    }
}
