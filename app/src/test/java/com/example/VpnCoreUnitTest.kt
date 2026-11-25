package com.example

import com.example.data.local.RelayProtocol
import com.example.data.model.ConfigParser
import com.example.vpn.PacketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnCoreUnitTest {

    @Test
    fun parseSocks5Uri_extractsCredentialsHostPortAndLabel() {
        val result = ConfigParser.parseRelayConfig("socks5://alice:secret@relay.example.org:1080#CH-Zurich")
        assertTrue(result.isSuccess)
        val node = result.getOrThrow()
        assertEquals("CH-Zurich", node.name)
        assertEquals("CH", node.regionCode)
        assertEquals("relay.example.org", node.host)
        assertEquals(1080, node.port)
        assertEquals("alice", node.username)
        assertEquals("secret", node.password)
        assertEquals(RelayProtocol.SOCKS5.name, node.protocol)
    }

    @Test
    fun parseWireGuardConf_extractsEndpointAndKeys() {
        val conf = """
            [Interface]
            PrivateKey = cHJpdmF0ZWtleQ==
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = cHVibGlja2V5
            Endpoint = 198.51.100.42:51820
        """.trimIndent()

        val result = ConfigParser.parseRelayConfig(conf)
        assertTrue(result.isSuccess)
        val node = result.getOrThrow()
        assertEquals("198.51.100.42", node.host)
        assertEquals(51820, node.port)
        assertEquals(RelayProtocol.WIREGUARD.name, node.protocol)
        assertEquals("cHVibGlja2V5", node.wgPublicKey)
    }

    @Test
    fun buildAndParseIpv4UdpPacket_roundTripsCleanly() {
        val srcIp = byteArrayOf(1, 1, 1, 1)
        val dstIp = byteArrayOf(10, 88, 0, 2)
        val payload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01)

        val packet = PacketParser.buildIpv4UdpResponse(
            srcIpBytes = srcIp,
            dstIpBytes = dstIp,
            srcPort = 53,
            dstPort = 45678,
            udpPayload = payload
        )

        val parsed = PacketParser.parseIpv4(packet, packet.size)
        assertNotNull(parsed)
        assertEquals("UDP", parsed!!.protocolName)
        assertEquals("1.1.1.1", parsed.srcIp)
        assertEquals("10.88.0.2", parsed.dstIp)
        assertEquals(53, parsed.srcPort)
        assertEquals(45678, parsed.dstPort)
        assertEquals(payload.size, parsed.payloadLength)
    }
}
