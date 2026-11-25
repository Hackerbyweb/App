package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connection_logs")
data class ConnectionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chainName: String,
    val hopSummary: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val status: String,
    val details: String = ""
)
