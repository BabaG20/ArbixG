package com.arbixg.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable local journal of exchange order state; contains no API credentials. */
@Entity(tableName = "tracked_orders")
data class TrackedOrderEntity(
    @PrimaryKey val orderId: String,
    val instrumentId: String,
    val status: String,
    val requestedSize: String,
    val cumulativeFilledSize: String,
    val remainingSize: String,
    val averageFillPrice: String?,
    val cumulativeFee: String,
    val feeCurrency: String?,
    val lastExchangeUpdateEpochMs: Long,
    val locallyUpdatedAtEpochMs: Long
)

fun ReconciledOrder.toEntity(nowEpochMs: Long = System.currentTimeMillis()) = TrackedOrderEntity(
    orderId = orderId,
    instrumentId = instrumentId,
    status = status.name,
    requestedSize = requestedSize,
    cumulativeFilledSize = cumulativeFilledSize,
    remainingSize = remainingSize,
    averageFillPrice = averageFillPrice,
    cumulativeFee = cumulativeFee,
    feeCurrency = feeCurrency,
    lastExchangeUpdateEpochMs = lastExchangeUpdateEpochMs,
    locallyUpdatedAtEpochMs = nowEpochMs
)

fun TrackedOrderEntity.toReconciledOrder() = ReconciledOrder(
    orderId = orderId,
    instrumentId = instrumentId,
    status = ReconciledOrderStatus.valueOf(status),
    requestedSize = requestedSize,
    cumulativeFilledSize = cumulativeFilledSize,
    remainingSize = remainingSize,
    averageFillPrice = averageFillPrice,
    cumulativeFee = cumulativeFee,
    feeCurrency = feeCurrency,
    lastExchangeUpdateEpochMs = lastExchangeUpdateEpochMs
)
