package com.arbixg.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface RiskStateDao {
    @Upsert
    suspend fun upsertState(state: RiskStateEntity)

    @Query("SELECT * FROM risk_state WHERE id = 1 LIMIT 1")
    suspend fun getState(): RiskStateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReservations(reservations: List<RiskReservationEntity>)

    @Query("SELECT * FROM risk_reservations WHERE currency = :currency")
    suspend fun getReservationsForCurrency(currency: String): List<RiskReservationEntity>

    @Query("SELECT * FROM risk_reservations WHERE cycleId = :cycleId")
    suspend fun getReservationsForCycle(cycleId: String): List<RiskReservationEntity>

    @Query("SELECT DISTINCT cycleId FROM risk_reservations ORDER BY cycleId")
    suspend fun getActiveCycleIds(): List<String>

    @Query("DELETE FROM risk_reservations WHERE cycleId = :cycleId")
    suspend fun releaseCycle(cycleId: String): Int

    @Query("DELETE FROM risk_reservations")
    suspend fun clearReservations(): Int
}
