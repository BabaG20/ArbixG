package com.arbixg.app.data

import java.math.BigDecimal

data class RiskLimits(
    val maxCycleStartAmount: BigDecimal,
    val maxDailyRealizedLoss: BigDecimal,
    val maxSlippageBps: BigDecimal,
    val maxSnapshotAgeMs: Long,
    val killSwitchEnabled: Boolean = false
) {
    init {
        require(maxCycleStartAmount > BigDecimal.ZERO)
        require(maxDailyRealizedLoss > BigDecimal.ZERO)
        require(maxSlippageBps >= BigDecimal.ZERO)
        require(maxSnapshotAgeMs > 0L)
    }
}

data class RiskBalance(
    val available: BigDecimal,
    val retrievedAtEpochMs: Long
)

data class RiskDecision(val approved: Boolean, val reason: String)

data class CycleFundsReservation(
    val cycleId: String,
    val startCurrency: String,
    val startAmount: BigDecimal,
    val requirements: Map<String, BigDecimal>,
    val reservedAtEpochMs: Long
)

/**
 * In-memory pre-trade risk gate. It does not submit orders and is not a substitute
 * for a durable reservation ledger, exchange-side reconciliation, or a kill switch
 * enforced by the execution service. Persist reservations before enabling execution.
 */
class CycleRiskGuard(
    private val limits: RiskLimits,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val reservations = LinkedHashMap<String, CycleFundsReservation>()
    private var dailyRealizedLoss = BigDecimal.ZERO

    @Synchronized
    fun reserve(
        cycleId: String,
        startCurrency: String,
        startAmount: BigDecimal,
        requiredFunds: Map<String, BigDecimal>,
        balances: Map<String, RiskBalance>,
        marketSnapshotEpochMs: Long,
        estimatedSlippageBps: BigDecimal
    ): RiskDecision {
        if (limits.killSwitchEnabled) return RiskDecision(false, "Kill switch is enabled.")
        if (cycleId.isBlank() || startCurrency.isBlank()) return RiskDecision(false, "Cycle ID and start currency are required.")
        if (reservations.containsKey(cycleId)) return RiskDecision(false, "Cycle already has an active reservation.")
        if (startAmount <= BigDecimal.ZERO || startAmount > limits.maxCycleStartAmount) {
            return RiskDecision(false, "Starting amount is outside the configured cycle limit.")
        }
        if (dailyRealizedLoss >= limits.maxDailyRealizedLoss) {
            return RiskDecision(false, "Daily realized-loss limit has been reached.")
        }
        if (estimatedSlippageBps < BigDecimal.ZERO || estimatedSlippageBps > limits.maxSlippageBps) {
            return RiskDecision(false, "Estimated slippage exceeds the configured limit.")
        }

        val now = clock()
        if (marketSnapshotEpochMs <= 0L || marketSnapshotEpochMs > now + 5_000L ||
            now - marketSnapshotEpochMs > limits.maxSnapshotAgeMs) {
            return RiskDecision(false, "Market snapshot is stale or has an invalid timestamp.")
        }
        if (requiredFunds.isEmpty() || requiredFunds.any { (currency, amount) ->
                currency.isBlank() || amount <= BigDecimal.ZERO
            }) {
            return RiskDecision(false, "Every required currency amount must be positive.")
        }

        for ((currency, amount) in requiredFunds) {
            val balance = balances[currency]
                ?: return RiskDecision(false, "Available balance for $currency is missing.")
            if (balance.available < BigDecimal.ZERO || balance.retrievedAtEpochMs <= 0L ||
                balance.retrievedAtEpochMs > now + 5_000L ||
                now - balance.retrievedAtEpochMs > limits.maxSnapshotAgeMs) {
                return RiskDecision(false, "Available balance for $currency is stale or invalid.")
            }
            val alreadyReserved = reservations.values.fold(BigDecimal.ZERO) { total, reservation ->
                total + (reservation.requirements[currency] ?: BigDecimal.ZERO)
            }
            if (balance.available - alreadyReserved < amount) {
                return RiskDecision(false, "Insufficient unreserved $currency balance.")
            }
        }

        reservations[cycleId] = CycleFundsReservation(
            cycleId = cycleId,
            startCurrency = startCurrency,
            startAmount = startAmount,
            requirements = requiredFunds.toMap(),
            reservedAtEpochMs = now
        )
        return RiskDecision(true, "Funds reserved locally for risk-checking; no exchange order was submitted.")
    }

    @Synchronized
    fun release(cycleId: String): Boolean = reservations.remove(cycleId) != null

    @Synchronized
    fun recordRealizedLoss(loss: BigDecimal): RiskDecision {
        if (loss < BigDecimal.ZERO) return RiskDecision(false, "Realized loss cannot be negative.")
        dailyRealizedLoss += loss
        return if (dailyRealizedLoss >= limits.maxDailyRealizedLoss) {
            RiskDecision(false, "Daily realized-loss limit reached; new cycles must stop.")
        } else {
            RiskDecision(true, "Realized loss recorded.")
        }
    }

    @Synchronized
    fun activeReservations(): List<CycleFundsReservation> = reservations.values.toList()

    @Synchronized
    fun currentDailyRealizedLoss(): BigDecimal = dailyRealizedLoss
}
