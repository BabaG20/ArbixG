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

/** Parsers for read-only OKX Earn responses. Unknown/missing amounts are ignored, never guessed. */
object OkxEarnResponseParser {
    fun parseSimpleEarn(data: org.json.JSONArray): List<OkxEarnHolding> =
        parseCurrencyAmounts(data, "Simple Earn")

    fun parseEthStaking(data: org.json.JSONArray): List<OkxEarnHolding> =
        parseCurrencyAmounts(data, "ETH staking")

    fun parseSolStaking(data: org.json.JSONArray): List<OkxEarnHolding> =
        parseCurrencyAmounts(data, "SOL staking")

    fun parseOkusd(data: org.json.JSONArray): List<OkxEarnHolding> =
        parseCurrencyAmounts(data, "OKUSD Earn")

    fun parseOnChainActive(data: org.json.JSONArray): List<OkxEarnHolding> {
        val holdings = ArrayList<OkxEarnHolding>()
        for (i in 0 until data.length()) {
            val order = data.optJSONObject(i) ?: continue
            val investments = order.optJSONArray("investData") ?: continue
            for (j in 0 until investments.length()) {
                val investment = investments.optJSONObject(j) ?: continue
                val currency = investment.optString("ccy").uppercase()
                val amount = investment.optString("amt")
                if (currency.isNotBlank() && OkxEarnHolding(currency, amount, "On-chain Earn").hasPositiveAmount()) {
                    holdings += OkxEarnHolding(currency, amount, "On-chain Earn")
                }
            }
        }
        return holdings
    }

    private fun parseCurrencyAmounts(data: org.json.JSONArray, product: String): List<OkxEarnHolding> {
        val holdings = ArrayList<OkxEarnHolding>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val currency = item.optString("ccy").uppercase()
            val amount = item.optString("amt")
            val holding = OkxEarnHolding(currency, amount, product)
            if (currency.isNotBlank() && holding.hasPositiveAmount()) holdings += holding
        }
        return holdings
    }
}
