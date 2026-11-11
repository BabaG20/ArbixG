package com.arbixg.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable intent written before any exchange submission.
 * Contains order parameters and state only; never API credentials or signatures.
 */
@Entity(
    tableName = "order_intents",
    indices = [
        Index(value = ["cycleId", "legIndex"], unique = true),
        Index(value = ["state", "updatedAtEpochMs"])
    ]
)
data class OrderIntentEntity(
    @PrimaryKey val clientOrderId: String,
    val cycleId: String,
    val legIndex: Int,
    val instrumentId: String,
    val side: String,
    val orderType: String,
    val price: String,
    val size: String,
    val state: String,
    val exchangeOrderId: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastError: String?
) {
    init {
        require(clientOrderId.matches(Regex("[A-Za-z0-9]{1,32}")))
        require(cycleId.isNotBlank())
        require(legIndex in 0..2)
        require(instrumentId.isNotBlank())
        require(side == "buy" || side == "sell")
        require(orderType == "limit")
        require(price.toBigDecimalOrNull()?.signum() == 1)
        require(size.toBigDecimalOrNull()?.signum() == 1)
        require(state in VALID_STATES)
    }

    companion object {
        const val PREPARED = "PREPARED"
        const val SUBMITTING = "SUBMITTING"
        const val ACKNOWLEDGED = "ACKNOWLEDGED"
        const val UNKNOWN = "UNKNOWN"
        const val RECONCILED = "RECONCILED"
        const val FAILED = "FAILED"

        val RECOVERABLE_STATES = listOf(PREPARED, SUBMITTING, UNKNOWN, ACKNOWLEDGED)
        private val VALID_STATES = setOf(PREPARED, SUBMITTING, ACKNOWLEDGED, UNKNOWN, RECONCILED, FAILED)
    }
}
