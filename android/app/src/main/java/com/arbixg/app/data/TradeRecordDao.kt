package com.arbixg.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TradeRecordDao {
    @Insert
    suspend fun insert(record: TradeRecord): Long

    @Insert
    suspend fun insertAll(records: List<TradeRecord>)

    @Query("SELECT * FROM trade_records ORDER BY createdAtEpochMs DESC LIMIT 500")
    fun observeRecent(): Flow<List<TradeRecord>>

    @Query("SELECT * FROM trade_records ORDER BY createdAtEpochMs DESC")
    suspend fun getAllForBackup(): List<TradeRecord>

    @Query("DELETE FROM trade_records")
    suspend fun deleteAll()
}
