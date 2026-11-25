package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hop_chains")
data class HopChainEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val entryNodeId: Long,
    val middleNodeId: Long? = null,
    val exitNodeId: Long,
    val cipherSuite: String = "ChaCha20-Poly1305",
    val isLocalTunInspection: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
