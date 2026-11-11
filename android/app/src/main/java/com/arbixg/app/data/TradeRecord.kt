package com.arbixg.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trade_records")
data class TradeRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val route: String,
    val status: String,
    val createdAtEpochMs: Long,
    val realizedPnl: Double? = null
)
