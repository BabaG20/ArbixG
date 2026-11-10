package com.arbixg.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [TradeRecord::class], version = 1, exportSchema = true)
abstract class ArbixDatabase : RoomDatabase() {
    abstract fun tradeRecordDao(): TradeRecordDao
}
