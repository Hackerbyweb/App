package com.example.vpn

import android.net.VpnService
import com.example.data.local.RelayNodeEntity
import com.example.data.local.RelayProtocol
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Establishes chained multi-hop connections across 1..N relay nodes (SOCKS5 RFC 1928 / HTTP CONNECT)
 * using VpnService.protect(socket) so upstream relay traffic bypasses the local TUN interface.
 */
object MultiHopRelayConnector {

    fun openChainedSocket(
        vpnService: VpnService?,
        hops: List<RelayNodeEntity>,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int = 5000,
        onHopProgress: (index: Int, hop: RelayNodeEntity) -> Unit = { _, _ -> }
    ): Socket {
        require(hops.isNotEmpty()) { "Relay chain must have at least one hop" }
        val entry = hops.first()
        val socket = Socket()
        vpnService?.protect(socket)
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress(entry.host, entry.port), timeoutMs)

        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            for (i in hops.indices) {
                val currentHop = hops[i]
                onHopProgress(i, currentHop)

                val nextHost = if (i < hops.lastIndex) hops[i + 1].host else targetHost
                val nextPort = if (i < hops.lastIndex) hops[i + 1].port else targetPort

                when (currentHop.protocolEnum()) {
                    RelayProtocol.SOCKS5 -> {
                        performSocks5Connect(
                            input = input,
                            output = output,
                            username = currentHop.username,
                            password = currentHop.password,
                            destHost = nextHost,
                            destPort = nextPort
                        )
                    }
                    RelayProtocol.HTTP_CONNECT -> {
                        performHttpConnect(
                            input = input,
                            output = output,
                            destHost = nextHost,
                            destPort = nextPort,
                            username = currentHop.username,
                            password = currentHop.password
                        )
                    }
                    RelayProtocol.WIREGUARD, RelayProtocol.LOCAL_TUN -> {
                        throw IOException("Hop ${currentHop.name} uses ${currentHop.protocolEnum().displayName}; TCP chaining requires SOCKS5 or HTTP Tunnel relay")
                    }
                }
            }
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /**
     * Probes whether a multi-hop chain can complete end-to-end handshake to a destination (e.g. 1.1.1.1:53).
     */
    fun verifyChainConnectivity(
        vpnService: VpnService?,
        hops: List<RelayNodeEntity>,
        probeHost: String = "1.1.1.1",
        probePort: Int = 53,
        onHopProgress: (index: Int, hop: RelayNodeEntity) -> Unit
    ): Result<Long> {
        val start = System.currentTimeMillis()
        return runCatching {
            val socket = openChainedSocket(
                vpnService = vpnService,
                hops = hops,
                targetHost = probeHost,
                targetPort = probePort,
                timeoutMs = 5000,
                onHopProgress = onHopProgress
            )
            socket.close()
            System.currentTimeMillis() - start
        }
    }

    /**
     * Forwards a raw DNS UDP payload over DNS-over-TCP (RFC 1035 2-byte length prefix)
     * through the multi-hop relay chain and returns the DNS response payload.
     */
    fun forwardDnsOverTcp(
        vpnService: VpnService?,
        hops: List<RelayNodeEntity>,
        dnsServer: String,
        dnsUdpPayload: ByteArray
    ): ByteArray? {
        return runCatching {
            openChainedSocket(
                vpnService = vpnService,
                hops = hops,
                targetHost = dnsServer,
                targetPort = 53,
                timeoutMs = 4000
            ).use { socket ->
                val out = socket.getOutputStream()
                val len = dnsUdpPayload.size
                val framed = ByteArray(len + 2)
                framed[0] = ((len ushr 8) and 0xFF).toByte()
                framed[1] = (len and 0xFF).toByte()
                System.arraycopy(dnsUdpPayload, 0, framed, 2, len)
                out.write(framed)
                out.flush()

                val dataIn = DataInputStream(socket.getInputStream())
                val respLen = dataIn.readUnsignedShort()
                if (respLen <= 0 || respLen > 4096) return@use null
                val resp = ByteArray(respLen)
                dataIn.readFully(resp)
                resp
            }
        }.getOrNull()
    }

    private fun performSocks5Connect(
        input: InputStream,
        output: OutputStream,
        username: String,
        password: String,
        destHost: String,
        destPort: Int
    ) {
        val hasAuth = username.isNotEmpty()
        if (hasAuth) {
            output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            output.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        output.flush()

        val greeting = ByteArray(2)
        readExact(input, greeting)
        if (greeting[0].toInt() != 0x05) {
            throw IOException("Invalid SOCKS5 version from relay")
        }

        when (greeting[1].toInt() and 0xFF) {
            0x00 -> Unit // No auth
            0x02 -> {
                if (!hasAuth) throw IOException("Relay requested username/password auth")
                val uBytes = username.toByteArray(Charsets.UTF_8)
                val pBytes = password.toByteArray(Charsets.UTF_8)
                val authReq = ByteArray(3 + uBytes.size + pBytes.size)
                authReq[0] = 0x01
                authReq[1] = uBytes.size.toByte()
                System.arraycopy(uBytes, 0, authReq, 2, uBytes.size)
                authReq[2 + uBytes.size] = pBytes.size.toByte()
                System.arraycopy(pBytes, 0, authReq, 3 + uBytes.size, pBytes.size)
                output.write(authReq)
                output.flush()
                val authResp = ByteArray(2)
                readExact(input, authResp)
                if (authResp[1].toInt() != 0x00) {
                    throw IOException("SOCKS5 authentication rejected by relay")
                }
            }
            else -> throw IOException("SOCKS5 auth method unsupported (${greeting[1].toInt() and 0xFF})")
        }

        val hostBytes = destHost.toByteArray(Charsets.US_ASCII)
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05 // VER
        req[1] = 0x01 // CMD CONNECT
        req[2] = 0x00 // RSV
        req[3] = 0x03 // ATYP DOMAINNAME
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((destPort ushr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (destPort and 0xFF).toByte()
        output.write(req)
        output.flush()

        val respHeader = ByteArray(4)
        readExact(input, respHeader)
        if (respHeader[1].toInt() != 0x00) {
            throw IOException("SOCKS5 CONNECT failed with code ${respHeader[1].toInt() and 0xFF}")
        }

        when (respHeader[3].toInt() and 0xFF) {
            0x01 -> readExact(input, ByteArray(6)) // IPv4 + port
            0x03 -> {
                val len = input.read()
                if (len > 0) readExact(input, ByteArray(len + 2))
            }
            0x04 -> readExact(input, ByteArray(18)) // IPv6 + port
        }
    }

    private fun performHttpConnect(
        input: InputStream,
        output: OutputStream,
        destHost: String,
        destPort: Int,
        username: String,
        password: String
    ) {
        val reqBuilder = StringBuilder()
            .append("CONNECT ").append(destHost).append(":").append(destPort).append(" HTTP/1.1\r\n")
            .append("Host: ").append(destHost).append(":").append(destPort).append("\r\n")
        if (username.isNotEmpty()) {
            val creds = android.util.Base64.encodeToString(
                "$username:$password".toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            reqBuilder.append("Proxy-Authorization: Basic ").append(creds).append("\r\n")
        }
        reqBuilder.append("\r\n")
        output.write(reqBuilder.toString().toByteArray(Charsets.US_ASCII))
        output.flush()

        val headerBytes = StringBuilder()
        var prevFour = ""
        while (!prevFour.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b == -1) throw IOException("Unexpected EOF during HTTP CONNECT")
            val c = b.toChar()
            headerBytes.append(c)
            prevFour = (prevFour + c).takeLast(4)
            if (headerBytes.length > 8192) throw IOException("HTTP CONNECT header too large")
        }
        val statusLine = headerBytes.toString().lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains(" 200")) {
            throw IOException("HTTP CONNECT rejected: $statusLine")
        }
    }

    private fun readExact(input: InputStream, dest: ByteArray) {
        var offset = 0
        while (offset < dest.size) {
            val read = input.read(dest, offset, dest.size - offset)
            if (read == -1) throw IOException("Unexpected EOF from relay")
            offset += read
        }
    }
}
