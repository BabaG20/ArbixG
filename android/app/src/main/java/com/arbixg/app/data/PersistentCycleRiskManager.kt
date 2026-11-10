package com.arbixg.app.data

import androidx.room.withTransaction
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * Room-backed pre-trade risk gate. All reservation checks and writes run in one
 * database transaction, so concurrent callers cannot reserve the same funds twice.
 * This class does not submit or authorize exchange orders.
 */
class PersistentCycleRiskManager(
    private val database: ArbixDatabase,
    private val limits: RiskLimits,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val dao get() = database.riskStateDao()

    suspend fun reserve(
        cycleId: String,
        startCurrency: String,
        startAmount: BigDecimal,
        requiredFunds: Map<String, BigDecimal>,
        balances: Map<String, RiskBalance>,
        marketSnapshotEpochMs: Long,
        estimatedSlippageBps: BigDecimal
    ): RiskDecision = database.withTransaction {
        val now = clock()
        val today = utcDate(now)
        val state = dao.getState()?.let {
            if (it.utcDate == today) it else it.copy(
                utcDate = today,
                dailyRealizedLoss = "0",
                updatedAtEpochMs = now
            )
        } ?: RiskStateEntity(utcDate = today, dailyRealizedLoss = "0", killSwitchEnabled = false, updatedAtEpochMs = now)

        if (state.utcDate != dao.getState()?.utcDate) dao.upsertState(state)
        if (limits.killSwitchEnabled || state.killSwitchEnabled) return@withTransaction denied("Kill switch is enabled.")
        if (cycleId.isBlank() || startCurrency.isBlank()) return@withTransaction denied("Cycle ID and start currency are required.")
        if (dao.getReservationsForCycle(cycleId).isNotEmpty()) return@withTransaction denied("Cycle already has an active reservation.")
        if (startAmount <= BigDecimal.ZERO || startAmount > limits.maxCycleStartAmount) {
            return@withTransaction denied("Starting amount is outside the configured cycle limit.")
        }
        if (BigDecimal(state.dailyRealizedLoss) >= limits.maxDailyRealizedLoss) {
            return@withTransaction denied("Daily realized-loss limit has been reached.")
        }
        if (estimatedSlippageBps < BigDecimal.ZERO || estimatedSlippageBps > limits.maxSlippageBps) {
            return@withTransaction denied("Estimated slippage exceeds the configured limit.")
        }
        if (marketSnapshotEpochMs <= 0L || marketSnapshotEpochMs > now + 5_000L ||
            now - marketSnapshotEpochMs > limits.maxSnapshotAgeMs) {
            return@withTransaction denied("Market snapshot is stale or has an invalid timestamp.")
        }
        if (requiredFunds.isEmpty() || requiredFunds.any { (currency, amount) ->
                currency.isBlank() || amount <= BigDecimal.ZERO
            }) return@withTransaction denied("Every required currency amount must be positive.")

        for ((currency, amount) in requiredFunds) {
            val balance = balances[currency]
                ?: return@withTransaction denied("Available balance for $currency is missing.")
            if (balance.available < BigDecimal.ZERO || balance.retrievedAtEpochMs <= 0L ||
                balance.retrievedAtEpochMs > now + 5_000L ||
                now - balance.retrievedAtEpochMs > limits.maxSnapshotAgeMs) {
                return@withTransaction denied("Available balance for $currency is stale or invalid.")
            }
            val alreadyReserved = dao.getReservationsForCurrency(currency).fold(BigDecimal.ZERO) { total, row ->
                total + BigDecimal(row.amount)
            }
            if (balance.available - alreadyReserved < amount) {
                return@withTransaction denied("Insufficient unreserved $currency balance.")
            }
        }

        dao.insertReservations(requiredFunds.map { (currency, amount) ->
            RiskReservationEntity(
                cycleId = cycleId,
                currency = currency,
                amount = amount.toPlainString(),
                startCurrency = startCurrency,
                startAmount = startAmount.toPlainString(),
                reservedAtEpochMs = now
            )
        })
        RiskDecision(true, "Funds reserved durably in Room; no exchange order was submitted.")
    }

    suspend fun release(cycleId: String): Boolean = database.withTransaction {
        dao.releaseCycle(cycleId) > 0
    }

    suspend fun recordRealizedLoss(loss: BigDecimal): RiskDecision = database.withTransaction {
        if (loss < BigDecimal.ZERO) return@withTransaction denied("Realized loss cannot be negative.")
        val now = clock()
        val today = utcDate(now)
        val previous = dao.getState()
        val baseLoss = if (previous?.utcDate == today) BigDecimal(previous.dailyRealizedLoss) else BigDecimal.ZERO
        val totalLoss = baseLoss + loss
        val killSwitch = previous?.killSwitchEnabled ?: false
        dao.upsertState(
            RiskStateEntity(
                utcDate = today,
                dailyRealizedLoss = totalLoss.toPlainString(),
                killSwitchEnabled = killSwitch,
                updatedAtEpochMs = now
            )
        )
        if (totalLoss >= limits.maxDailyRealizedLoss) {
            denied("Daily realized-loss limit reached; new cycles must stop.")
        } else {
            RiskDecision(true, "Realized loss recorded durably.")
        }
    }

    suspend fun setKillSwitchEnabled(enabled: Boolean) = database.withTransaction {
        val now = clock()
        val previous = dao.getState()
        val today = utcDate(now)
        val loss = if (previous?.utcDate == today) previous.dailyRealizedLoss else "0"
        dao.upsertState(
            RiskStateEntity(
                utcDate = today,
                dailyRealizedLoss = loss,
                killSwitchEnabled = enabled,
                updatedAtEpochMs = now
            )
        )
    }

    suspend fun isKillSwitchEnabled(): Boolean = dao.getState()?.killSwitchEnabled ?: false

    suspend fun activeReservations(): List<CycleFundsReservation> =
        dao.getActiveCycleIds().mapNotNull { cycleId ->
            val rows = dao.getReservationsForCycle(cycleId)
            val first = rows.firstOrNull() ?: return@mapNotNull null
            CycleFundsReservation(
                cycleId = cycleId,
                startCurrency = first.startCurrency,
                startAmount = BigDecimal(first.startAmount),
                requirements = rows.associate { it.currency to BigDecimal(it.amount) },
                reservedAtEpochMs = first.reservedAtEpochMs
            )
        }

    suspend fun currentDailyRealizedLoss(): BigDecimal {
        val state = dao.getState() ?: return BigDecimal.ZERO
        return if (state.utcDate == utcDate(clock())) BigDecimal(state.dailyRealizedLoss) else BigDecimal.ZERO
    }

    private fun utcDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun denied(reason: String) = RiskDecision(false, reason)
}
