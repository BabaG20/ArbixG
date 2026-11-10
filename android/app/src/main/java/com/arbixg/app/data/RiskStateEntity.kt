package com.arbixg.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row per currency reserved by a cycle; decimal amounts stay exact as text. */
@Entity(tableName = "risk_reservations", primaryKeys = ["cycleId", "currency"])
data class RiskReservationEntity(
    val cycleId: String,
    val currency: String,
    val amount: String,
    val startCurrency: String,
    val startAmount: String,
    val reservedAtEpochMs: Long
)

/** Singleton durable risk state. The date is UTC (yyyy-MM-dd). */
@Entity(tableName = "risk_state")
data class RiskStateEntity(
    @PrimaryKey val id: Int = 1,
    val utcDate: String,
    val dailyRealizedLoss: String,
    val killSwitchEnabled: Boolean,
    val updatedAtEpochMs: Long
)
