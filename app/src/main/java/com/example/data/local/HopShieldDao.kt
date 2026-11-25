package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HopShieldDao {

    @Query("SELECT * FROM relay_nodes ORDER BY createdAt DESC")
    fun observeNodes(): Flow<List<RelayNodeEntity>>

    @Query("SELECT * FROM relay_nodes")
    suspend fun getAllNodes(): List<RelayNodeEntity>

    @Query("SELECT * FROM relay_nodes WHERE id = :id LIMIT 1")
    suspend fun getNodeById(id: Long): RelayNodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: RelayNodeEntity): Long

    @Update
    suspend fun updateNode(node: RelayNodeEntity)

    @Query("DELETE FROM relay_nodes WHERE id = :id")
    suspend fun deleteNodeById(id: Long)

    @Query("SELECT * FROM hop_chains ORDER BY createdAt DESC")
    fun observeChains(): Flow<List<HopChainEntity>>

    @Query("SELECT * FROM hop_chains")
    suspend fun getAllChains(): List<HopChainEntity>

    @Query("SELECT * FROM hop_chains WHERE id = :id LIMIT 1")
    suspend fun getChainById(id: Long): HopChainEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChain(chain: HopChainEntity): Long

    @Query("DELETE FROM hop_chains WHERE id = :id")
    suspend fun deleteChainById(id: Long)

    @Query("SELECT * FROM connection_logs ORDER BY startedAt DESC LIMIT 50")
    fun observeLogs(): Flow<List<ConnectionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ConnectionLogEntity): Long

    @Query("UPDATE connection_logs SET endedAt = :endedAt, rxBytes = :rxBytes, txBytes = :txBytes, status = :status, details = :details WHERE id = :id")
    suspend fun finishLog(id: Long, endedAt: Long, rxBytes: Long, txBytes: Long, status: String, details: String)

    @Query("DELETE FROM connection_logs")
    suspend fun clearLogs()
}
