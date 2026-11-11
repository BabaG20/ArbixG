package com.arbixg.app.data

import java.math.BigDecimal

data class OkxEarnHolding(
    val currency: String,
    val amount: String,
    val product: String
) {
    fun hasPositiveAmount(): Boolean =
        amount.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) == 1
}

data class OkxAssetExclusionSnapshot(
    val spotHeldCurrencies: Set<String>,
    val earnHeldCurrencies: Set<String>,
    val openSpotOrderInstrumentIds: Set<String>,
    val retrievedAtEpochMs: Long,
    val complete: Boolean
) {
    val blockedBaseCurrencies: Set<String>
        get() = (spotHeldCurrencies + earnHeldCurrencies).map { it.uppercase() }.toSet()
}

data class OpportunityFilterResult(
    val included: List<TriangularCandidate>,
    val excluded: List<TriangularCandidate>,
    val blockedReasonsByPath: Map<String, Set<String>>
)

/**
 * Excludes a cycle when any leg's base asset is already held in Spot/Earn,
 * or that exact instrument has an open Spot order. Quote balances are not
 * treated as held base assets (otherwise a USDT starting balance would
 * suppress nearly every USDT-quoted market).
 */
object OpportunityAssetFilter {
    fun apply(
        candidates: List<TriangularCandidate>,
        tickers: List<SpotTicker>,
        exclusions: OkxAssetExclusionSnapshot
    ): OpportunityFilterResult {
        require(exclusions.complete) { "Spot, Earn and open-order snapshots must all be complete before asset filtering." }
        val baseByInstrument = tickers.associate { it.instrumentId to it.baseCurrency.uppercase() }
        val heldBases = exclusions.blockedBaseCurrencies
        val openOrders = exclusions.openSpotOrderInstrumentIds.map { it.uppercase() }.toSet()
        val included = ArrayList<TriangularCandidate>()
        val excluded = ArrayList<TriangularCandidate>()
        val reasons = LinkedHashMap<String, Set<String>>()

        candidates.forEach { candidate ->
            val blocked = candidate.instruments.mapNotNull { instrumentId ->
                val base = baseByInstrument[instrumentId.uppercase()]
                    ?: return@mapNotNull "${instrumentId}:base-currency-unknown"
                when {
                    instrumentId.uppercase() in openOrders -> "${instrumentId}:open-spot-order"
                    base in heldBases -> "${instrumentId}:base-held-$base"
                    else -> null
                }
            }.toSet()
            if (blocked.isEmpty()) included += candidate else {
                excluded += candidate
                reasons[candidate.path.joinToString(">")] = blocked
            }
        }
        return OpportunityFilterResult(included, excluded, reasons)
    }
}
