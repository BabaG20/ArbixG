package com.arbixg.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TradeRecordDao {
    @Insert
    suspend fun insert(record: TradeRecord): Long

    @Query("SELECT * FROM trade_records ORDER BY createdAtEpochMs DESC LIMIT 500")
    fun observeRecent(): Flow<List<TradeRecord>>
}
