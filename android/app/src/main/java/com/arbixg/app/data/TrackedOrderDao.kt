package com.arbixg.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TrackedOrderDao {
    @Upsert
    suspend fun upsert(order: TrackedOrderEntity)

    @Upsert
    suspend fun upsertAll(orders: List<TrackedOrderEntity>)

    @Query("SELECT * FROM tracked_orders ORDER BY locallyUpdatedAtEpochMs DESC LIMIT 500")
    suspend fun getRecent(): List<TrackedOrderEntity>

    @Query("SELECT * FROM tracked_orders WHERE orderId = :orderId LIMIT 1")
    suspend fun getByOrderId(orderId: String): TrackedOrderEntity?

    @Query("SELECT COUNT(*) FROM tracked_orders")
    suspend fun count(): Int
}
