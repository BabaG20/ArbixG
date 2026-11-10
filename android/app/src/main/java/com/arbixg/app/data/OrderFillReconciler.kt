package com.arbixg.app.data

import java.math.BigDecimal

enum class ReconciledOrderStatus {
    OPEN, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED
}

data class OkxOrderSnapshot(
    val orderId: String,
    val instrumentId: String,
    val exchangeState: String,
    val requestedSize: String,
    val cumulativeFilledSize: String,
    val averageFillPrice: String,
    val cumulativeFee: String,
    val feeCurrency: String?,
    val updateTimeEpochMs: Long
)

data class ReconciledOrder(
    val orderId: String,
    val instrumentId: String,
    val status: ReconciledOrderStatus,
    val requestedSize: String,
    val cumulativeFilledSize: String,
    val remainingSize: String,
    val averageFillPrice: String?,
    val cumulativeFee: String,
    val feeCurrency: String?,
    val lastExchangeUpdateEpochMs: Long
)

/**
 * Converts a full OKX order-query payload into the normalized snapshot used by
 * the fill reconciler. Sparse place acknowledgements are deliberately rejected:
 * the next leg must be based on a full exchange order query, not an ack.
 */
fun OkxDemoOrderResult.toOrderSnapshot(): OkxOrderSnapshot {
    val requested = raw.optString("sz")
    val filled = raw.optString("accFillSz")
    val average = raw.optString("avgPx")
    val fee = raw.optString("fee")
    val updateTime = raw.optString("uTime").toLongOrNull() ?: 0L
    val requestedValue = requested.toBigDecimalOrNull()
    val filledValue = filled.toBigDecimalOrNull()
    val averageValue = average.toBigDecimalOrNull()
    val feeValue = fee.toBigDecimalOrNull()
    require(requestedValue?.signum() == 1) {
        "OKX order query omitted a valid requested size."
    }
    require(filledValue != null && filledValue.signum() >= 0 && filledValue <= requestedValue) {
        "OKX order query omitted a valid cumulative filled size."
    }
    // OKX may return empty avgPx/fee/feeCcy while an order has no fills.
    // Once any quantity is filled, a positive average price and explicit fee are mandatory.
    if (filledValue.signum() > 0) {
        require(averageValue != null && averageValue.signum() > 0) {
            "OKX order query omitted a valid average fill price."
        }
        require(feeValue != null) {
            "OKX order query omitted the cumulative fee; realized proceeds cannot be trusted."
        }
    } else {
        require(average.isBlank() || averageValue?.signum() == 0) {
            "Unfilled order unexpectedly reported an average fill price."
        }
    }
    require(updateTime > 0L) { "OKX order query omitted a valid exchange update timestamp." }
    return OkxOrderSnapshot(
        orderId = orderId,
        instrumentId = instrumentId,
        exchangeState = raw.optString("state", state),
        requestedSize = requested,
        cumulativeFilledSize = filled,
        averageFillPrice = average,
        cumulativeFee = feeValue?.toPlainString() ?: "0",
        feeCurrency = raw.optString("feeCcy").takeIf { it.isNotBlank() },
        updateTimeEpochMs = updateTime
    )
}

/** Pure, idempotent reconciliation of OKX order snapshots. No order actions are sent. */
object OrderFillReconciler {
    fun reconcile(previous: ReconciledOrder?, incoming: OkxOrderSnapshot): ReconciledOrder {
        require(incoming.orderId.isNotBlank() && incoming.instrumentId.isNotBlank()) {
            "Order identity is missing."
        }
        require(incoming.updateTimeEpochMs > 0L) { "Exchange update timestamp is invalid." }
        val requested = decimal(incoming.requestedSize, "requested size")
        val filled = decimal(incoming.cumulativeFilledSize, "cumulative filled size")
        val average = incoming.averageFillPrice.toBigDecimalOrNull()
        val fee = incoming.cumulativeFee.toBigDecimalOrNull()
            ?: if (incoming.cumulativeFee.isBlank()) BigDecimal.ZERO else error("Cumulative fee is invalid.")
        require(requested > BigDecimal.ZERO) { "Requested size must be positive." }
        require(filled >= BigDecimal.ZERO && filled <= requested) {
            "Cumulative filled size must be between zero and requested size."
        }
        require(average == null || average >= BigDecimal.ZERO) { "Average fill price cannot be negative." }

        val status = when (incoming.exchangeState.lowercase()) {
            "live" -> if (filled > BigDecimal.ZERO) ReconciledOrderStatus.PARTIALLY_FILLED else ReconciledOrderStatus.OPEN
            "partially_filled" -> {
                require(filled > BigDecimal.ZERO && filled < requested) {
                    "Partially filled state requires a non-zero fill below requested size."
                }
                ReconciledOrderStatus.PARTIALLY_FILLED
            }
            "filled" -> {
                require(filled.compareTo(requested) == 0) {
                    "Exchange marked order filled but cumulative fill does not equal requested size."
                }
                ReconciledOrderStatus.FILLED
            }
            "canceled", "mmp_canceled" -> ReconciledOrderStatus.CANCELED
            "rejected" -> ReconciledOrderStatus.REJECTED
            else -> error("Unsupported OKX order state: ${incoming.exchangeState}")
        }

        if (previous != null) {
            require(previous.orderId == incoming.orderId && previous.instrumentId == incoming.instrumentId) {
                "Order identity changed during reconciliation."
            }
            if (incoming.updateTimeEpochMs < previous.lastExchangeUpdateEpochMs) return previous
            val previousFilled = decimal(previous.cumulativeFilledSize, "previous cumulative fill")
            require(filled >= previousFilled) {
                "Cumulative filled size regressed; refusing to overwrite reconciled fills."
            }
            if (previous.status in setOf(ReconciledOrderStatus.FILLED, ReconciledOrderStatus.CANCELED, ReconciledOrderStatus.REJECTED)) {
                return previous
            }
        }

        val remaining = (requested - filled).max(BigDecimal.ZERO)
        return ReconciledOrder(
            orderId = incoming.orderId,
            instrumentId = incoming.instrumentId,
            status = status,
            requestedSize = requested.stripTrailingZeros().toPlainString(),
            cumulativeFilledSize = filled.stripTrailingZeros().toPlainString(),
            remainingSize = remaining.stripTrailingZeros().toPlainString(),
            averageFillPrice = average?.stripTrailingZeros()?.toPlainString(),
            cumulativeFee = fee.stripTrailingZeros().toPlainString(),
            feeCurrency = incoming.feeCurrency?.takeIf { it.isNotBlank() },
            lastExchangeUpdateEpochMs = incoming.updateTimeEpochMs
        )
    }

    private fun decimal(value: String, label: String): BigDecimal =
        value.toBigDecimalOrNull() ?: error("$label is invalid.")
}
