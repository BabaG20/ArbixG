package com.arbixg.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
abstract class OrderIntentDao {
    /** Returns -1 when this client ID or cycle/leg already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(intent: OrderIntentEntity): Long

    @Update
    abstract suspend fun update(intent: OrderIntentEntity): Int

    @Query("SELECT * FROM order_intents WHERE clientOrderId = :clientOrderId LIMIT 1")
    abstract suspend fun get(clientOrderId: String): OrderIntentEntity?

    @Query("SELECT * FROM order_intents WHERE cycleId = :cycleId ORDER BY legIndex ASC")
    abstract suspend fun getForCycle(cycleId: String): List<OrderIntentEntity>

    @Query("""
        SELECT * FROM order_intents
        WHERE state IN ('PREPARED', 'SUBMITTING', 'UNKNOWN')
        ORDER BY createdAtEpochMs ASC
    """)
    abstract suspend fun getRecoverable(): List<OrderIntentEntity>

    @Query("SELECT COUNT(*) FROM order_intents")
    abstract suspend fun count(): Int

    @Query("""
        UPDATE order_intents
        SET state = :newState, exchangeOrderId = :exchangeOrderId,
            updatedAtEpochMs = :updatedAtEpochMs, lastError = :lastError
        WHERE clientOrderId = :clientOrderId AND state = :expectedState
    """)
    abstract suspend fun transition(
        clientOrderId: String,
        expectedState: String,
        newState: String,
        exchangeOrderId: String?,
        updatedAtEpochMs: Long,
        lastError: String?
    ): Int

    @Transaction
    open suspend fun markSubmitting(clientOrderId: String, now: Long): Boolean =
        transition(clientOrderId, OrderIntentEntity.PREPARED, OrderIntentEntity.SUBMITTING, null, now, null) == 1

    @Transaction
    open suspend fun markUnknown(clientOrderId: String, now: Long, reason: String): Boolean {
        val current = get(clientOrderId) ?: return false
        if (current.state !in listOf(OrderIntentEntity.SUBMITTING, OrderIntentEntity.ACKNOWLEDGED)) return false
        return transition(clientOrderId, current.state, OrderIntentEntity.UNKNOWN, current.exchangeOrderId, now, reason) == 1
    }

    @Transaction
    open suspend fun markAcknowledged(clientOrderId: String, exchangeOrderId: String, now: Long): Boolean {
        require(exchangeOrderId.isNotBlank())
        val current = get(clientOrderId) ?: return false
        if (current.state !in listOf(OrderIntentEntity.SUBMITTING, OrderIntentEntity.UNKNOWN)) return false
        return transition(clientOrderId, current.state, OrderIntentEntity.ACKNOWLEDGED, exchangeOrderId, now, null) == 1
    }
}
