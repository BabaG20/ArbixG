package com.arbixg.app.data

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class CycleRiskGuardTest {
    private val now = 1_800_000_000_000L
    private fun d(value: String) = BigDecimal(value)
    private fun limits(kill: Boolean = false) = RiskLimits(
        maxCycleStartAmount = d("10"),
        maxDailyRealizedLoss = d("2"),
        maxSlippageBps = d("20"),
        maxSnapshotAgeMs = 2_000,
        killSwitchEnabled = kill
    )
    private fun balances(usdt: String = "15", btc: String = "1") = mapOf(
        "USDT" to RiskBalance(d(usdt), now),
        "BTC" to RiskBalance(d(btc), now)
    )

    @Test fun reservesAllCurrenciesAndPreventsDoubleReservation() {
        val guard = CycleRiskGuard(limits()) { now }
        val requirements = mapOf("USDT" to d("8"), "BTC" to d("0.5"))
        assertTrue(guard.reserve("cycle-1", "USDT", d("8"), requirements, balances(), now, d("5")).approved)
        assertFalse(guard.reserve("cycle-2", "USDT", d("8"), requirements, balances(), now, d("5")).approved)
        assertTrue(guard.release("cycle-1"))
        assertTrue(guard.reserve("cycle-2", "USDT", d("8"), requirements, balances(), now, d("5")).approved)
    }

    @Test fun rejectsMissingOrStaleBalanceAndMarketData() {
        val guard = CycleRiskGuard(limits()) { now }
        val required = mapOf("USDT" to d("1"))
        assertFalse(guard.reserve("a", "USDT", d("1"), required, emptyMap(), now, d("1")).approved)
        assertFalse(guard.reserve("b", "USDT", d("1"), required, balances(), now - 3_000, d("1")).approved)
        val stale = mapOf("USDT" to RiskBalance(d("10"), now - 3_000))
        assertFalse(guard.reserve("c", "USDT", d("1"), required, stale, now, d("1")).approved)
    }

    @Test fun enforcesCycleSizeSlippageAndKillSwitch() {
        val required = mapOf("USDT" to d("1"))
        val guard = CycleRiskGuard(limits()) { now }
        assertFalse(guard.reserve("a", "USDT", d("11"), required, balances(), now, d("1")).approved)
        assertFalse(guard.reserve("b", "USDT", d("1"), required, balances(), now, d("21")).approved)
        val stopped = CycleRiskGuard(limits(kill = true)) { now }
        assertFalse(stopped.reserve("c", "USDT", d("1"), required, balances(), now, d("1")).approved)
    }

    @Test fun dailyLossLimitStopsFurtherCycles() {
        val guard = CycleRiskGuard(limits()) { now }
        assertFalse(guard.recordRealizedLoss(d("2")).approved)
        assertFalse(guard.reserve("a", "USDT", d("1"), mapOf("USDT" to d("1")), balances(), now, d("1")).approved)
    }
}
