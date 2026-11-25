package com.example.data.model

import com.example.data.local.RelayNodeEntity
import com.example.data.local.RelayProtocol
import java.net.URI

object ConfigParser {

    /**
     * Parses either a WireGuard .conf INI file/snippet or a proxy URI
     * (e.g., socks5://user:pass@host:1080#FR-Paris or http://host:8080#US-East)
     * into a RelayNodeEntity ready for verification and chaining.
     */
    fun parseRelayConfig(rawInput: String): Result<RelayNodeEntity> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Configuration input is empty"))
        }

        if (trimmed.contains("[Interface]", ignoreCase = true) ||
            trimmed.contains("[Peer]", ignoreCase = true)
        ) {
            return parseWireGuardConf(trimmed)
        }

        if (trimmed.startsWith("socks5://", ignoreCase = true) ||
            trimmed.startsWith("socks://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return parseProxyUri(trimmed)
        }

        // Fallback host:port quick notation
        val hostPortParts = trimmed.split(":")
        if (hostPortParts.size == 2) {
            val host = hostPortParts[0].trim()
            val port = hostPortParts[1].trim().toIntOrNull()
            if (host.isNotEmpty() && port != null && port in 1..65535) {
                return Result.success(
                    RelayNodeEntity(
                        name = "$host:$port",
                        regionCode = "CUSTOM",
                        city = host,
                        host = host,
                        port = port,
                        protocol = RelayProtocol.SOCKS5.name
                    )
                )
            }
        }

        return Result.failure(
            IllegalArgumentException("Unsupported format. Paste a WireGuard .conf, socks5:// URI, http:// URI, or host:port.")
        )
    }

    private fun parseProxyUri(uriText: String): Result<RelayNodeEntity> {
        return runCatching {
            val uri = URI(uriText)
            val scheme = uri.scheme?.lowercase() ?: "socks5"
            val protocol = when {
                scheme.startsWith("socks") -> RelayProtocol.SOCKS5
                scheme.startsWith("http") -> RelayProtocol.HTTP_CONNECT
                else -> RelayProtocol.SOCKS5
            }
            val host = uri.host?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing host in relay URI")
            val port = if (uri.port in 1..65535) uri.port else protocol.defaultPort
            val userInfo = uri.userInfo.orEmpty()
            val userParts = if (userInfo.contains(":")) userInfo.split(":", limit = 2) else listOf(userInfo, "")
            val username = userParts.getOrElse(0) { "" }
            val password = userParts.getOrElse(1) { "" }
            val fragment = uri.fragment?.takeIf { it.isNotBlank() }
            val label = fragment ?: "$host:$port"
            val region = fragment?.take(2)?.uppercase()?.takeIf { it.all(Char::isLetter) } ?: "NET"

            RelayNodeEntity(
                name = label,
                regionCode = region,
                city = fragment ?: host,
                host = host,
                port = port,
                protocol = protocol.name,
                username = username,
                password = password
            )
        }
    }

    private fun parseWireGuardConf(conf: String): Result<RelayNodeEntity> {
        var privateKey = ""
        var address = ""
        var publicKey = ""
        var endpoint = ""
        var section = ""

        conf.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore("#").trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.lowercase()
                return@forEach
            }
            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) return@forEach
            val key = line.substring(0, eqIdx).trim().lowercase()
            val value = line.substring(eqIdx + 1).trim()

            when (section) {
                "[interface]" -> when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> address = value
                }
                "[peer]" -> when (key) {
                    "publickey" -> publicKey = value
                    "endpoint" -> endpoint = value
                }
            }
        }

        if (endpoint.isBlank()) {
            return Result.failure(IllegalArgumentException("WireGuard config missing [Peer] Endpoint"))
        }

        val lastColon = endpoint.lastIndexOf(':')
        if (lastColon <= 0) {
            return Result.failure(IllegalArgumentException("Invalid WireGuard Endpoint format (expected host:port)"))
        }
        val host = endpoint.substring(0, lastColon).trim().removeSurrounding("[", "]")
        val port = endpoint.substring(lastColon + 1).trim().toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid WireGuard port"))

        return Result.success(
            RelayNodeEntity(
                name = "WG-$host",
                regionCode = "WG",
                city = host,
                host = host,
                port = port,
                protocol = RelayProtocol.WIREGUARD.name,
                wgPublicKey = publicKey,
                wgPrivateKey = privateKey,
                wgAddress = address
            )
        )
    }
}
