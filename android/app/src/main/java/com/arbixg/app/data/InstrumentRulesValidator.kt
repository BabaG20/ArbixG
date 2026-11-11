package com.arbixg.app.data

import java.math.BigDecimal

data class InstrumentRulesCheck(
    val passed: Boolean,
    val message: String
)

/** Validates the quantities actually simulated after lotSz flooring. */
object InstrumentRulesValidator {
    fun validate(
        path: List<String>,
        instrumentIds: List<String>,
        books: Map<String, SpotOrderBook>,
        rules: Map<String, SpotInstrumentRules>,
        legs: List<DepthLegResult>
    ): InstrumentRulesCheck {
        if (path.size != 4 || instrumentIds.size != 3 || legs.size != 3) {
            return InstrumentRulesCheck(false, "Invalid three-leg cycle structure.")
        }
        for (index in 0 until 3) {
            val id = instrumentIds[index]
            val rule = rules[id]
                ?: return InstrumentRulesCheck(false, "$id: OKX instrument rules are missing.")
            if (rule.state != "live") return InstrumentRulesCheck(false, "$id: instrument is not live.")
            val parts = id.split("-")
            if (parts.size != 2 || parts[0] != rule.baseCurrency || parts[1] != rule.quoteCurrency ||
                path[index] !in parts || path[index + 1] !in parts || path[index] == path[index + 1]) {
                return InstrumentRulesCheck(false, "$id: instrument currencies do not match this cycle leg.")
            }

            val lot = positiveDecimal(rule.lotSize)
                ?: return InstrumentRulesCheck(false, "$id: invalid OKX lotSz.")
            val minimum = positiveDecimal(rule.minimumSize)
                ?: return InstrumentRulesCheck(false, "$id: invalid OKX minSz.")
            val tick = positiveDecimal(rule.tickSize)
                ?: return InstrumentRulesCheck(false, "$id: invalid OKX tickSz.")
            val book = books[id] ?: return InstrumentRulesCheck(false, "$id: order book missing during rules check.")
            val leg = legs[index]
            val executedBase = BigDecimal.valueOf(leg.executedBaseQuantity)
            if (leg.executedBaseQuantity <= 0.0 || executedBase < minimum) {
                return InstrumentRulesCheck(false, "$id: rounded order size is below OKX minSz.")
            }
            if (executedBase.remainder(lot).compareTo(BigDecimal.ZERO) != 0) {
                return InstrumentRulesCheck(false, "$id: simulated base quantity is not aligned to OKX lotSz.")
            }
            if (!leg.dustAmount.isFinite() || leg.dustAmount < 0.0 ||
                (leg.dustAmount > 0.0 && leg.dustCurrency != path[index])) {
                return InstrumentRulesCheck(false, "$id: residual dust accounting is invalid.")
            }
            val badTick = (book.bids + book.asks).firstOrNull { level ->
                BigDecimal.valueOf(level.price).remainder(tick).compareTo(BigDecimal.ZERO) != 0
            }
            if (badTick != null) {
                return InstrumentRulesCheck(false, "$id: an order-book price is not aligned to OKX tickSz.")
            }
        }
        return InstrumentRulesCheck(
            true,
            "All three legs were re-simulated with lotSz flooring and pass live status, minSz, lotSz and tickSz checks. Residual dust is excluded from proceeds. minNotional is not exposed by the loaded Spot metadata and is not verified; fee currency/tier and account balance are also pending."
        )
    }

    private fun positiveDecimal(value: String): BigDecimal? =
        value.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
}
