package com.arbixg.app.data

object TriangularScanner {
    /**
     * Enumerates directed three-market cycles across every supplied live SPOT ticker.
     * Discovery only: candidates require fresh order-book depth, account fee tier,
     * instrument rules, balances and slippage validation before execution.
     */
    fun scanAll(tickers: List<SpotTicker>, limit: Int = Int.MAX_VALUE): List<TriangularCandidate> {
        val adjacency = HashMap<String, MutableList<ConversionEdge>>()
        for (ticker in tickers) {
            val bid = ticker.bid.toDoubleOrNull() ?: continue
            val ask = ticker.ask.toDoubleOrNull() ?: continue
            if (bid <= 0.0 || ask <= 0.0 || bid > ask) continue
            adjacency.getOrPut(ticker.baseCurrency) { mutableListOf() }.add(
                ConversionEdge(ticker.baseCurrency, ticker.quoteCurrency, bid, ticker.instrumentId)
            )
            adjacency.getOrPut(ticker.quoteCurrency) { mutableListOf() }.add(
                ConversionEdge(ticker.quoteCurrency, ticker.baseCurrency, 1.0 / ask, ticker.instrumentId)
            )
        }
        val found = HashMap<String, TriangularCandidate>()
        for ((start, firstEdges) in adjacency) {
            for (first in firstEdges) {
                for (second in adjacency[first.to].orEmpty()) {
                    if (second.to == start || second.to == first.from || second.instrumentId == first.instrumentId) continue
                    for (third in adjacency[second.to].orEmpty()) {
                        if (third.to != start || third.instrumentId == first.instrumentId ||
                            third.instrumentId == second.instrumentId) continue
                        val path = listOf(start, first.to, second.to, start)
                        val gross = first.rate * second.rate * third.rate
                        if (!gross.isFinite() || gross <= 1.0) continue
                        val key = (0..2).map { offset ->
                            (0..2).map { path[(offset + it) % 3] }.joinToString(">")
                        }.minOrNull() ?: continue
                        val candidate = TriangularCandidate(
                            path = path,
                            instruments = listOf(first.instrumentId, second.instrumentId, third.instrumentId),
                            grossProfitPct = (gross - 1.0) * 100.0,
                            feeStatus = "FEE_AND_DEPTH_NOT_VALIDATED"
                        )
                        if (found[key] == null || candidate.grossProfitPct > found.getValue(key).grossProfitPct) {
                            found[key] = candidate
                        }
                    }
                }
            }
        }
        return found.values.sortedByDescending { it.grossProfitPct }.take(limit.coerceAtLeast(1))
    }
}

private data class ConversionEdge(
    val from: String,
    val to: String,
    val rate: Double,
    val instrumentId: String
)
