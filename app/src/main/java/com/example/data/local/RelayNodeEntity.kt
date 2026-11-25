package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RelayProtocol(val displayName: String, val defaultPort: Int) {
    SOCKS5("SOCKS5", 1080),
    HTTP_CONNECT("HTTP Tunnel", 8080),
    WIREGUARD("WireGuard", 51820),
    LOCAL_TUN("Local TUN", 0)
}

@Entity(tableName = "relay_nodes")
data class RelayNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val regionCode: String,
    val city: String,
    val host: String,
    val port: Int,
    val protocol: String = RelayProtocol.SOCKS5.name,
    val username: String = "",
    val password: String = "",
    val wgPublicKey: String = "",
    val wgPrivateKey: String = "",
    val wgAddress: String = "",
    val lastLatencyMs: Int? = null,
    val lastCheckedAt: Long? = null,
    val isReachable: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun protocolEnum(): RelayProtocol =
        RelayProtocol.entries.firstOrNull { it.name == protocol } ?: RelayProtocol.SOCKS5
}
