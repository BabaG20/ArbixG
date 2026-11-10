package com.arbixg.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class DepthValidationStatus {
    DEPTH_PASSED_GROSS_ONLY,
    DEPTH_PASSED_WITH_FEE,
    DEPTH_REJECTED
}

data class DepthValidationItem(
    val candidate: TriangularCandidate,
    val status: DepthValidationStatus,
    val finalAmount: Double?,
    val grossReturnPct: Double?,
    val netReturnPct: Double?,
    val message: String
)

/**
 * Bounded public L2 validation for USDT-starting candidates.
 * Each unique instrument is downloaded once per batch, with at most four
 * concurrent requests. No credentials or orders are used.
 */
class DepthValidationEngine(
    private val loadOrderBook: suspend (String) -> SpotOrderBook,
    private val maxConcurrentRequests: Int = 4,
    private val maxCandidates: Int = 10
) {
    init {
        require(maxConcurrentRequests in 1..8)
        require(maxCandidates in 1..50)
    }

    suspend fun validateUsdtCandidates(
        candidates: List<TriangularCandidate>,
        startAmountUsdt: Double = 10.0,
        feeRate: Double? = null,
        now: Long = System.currentTimeMillis(),
        maxBookAgeMs: Long = 2_000L
    ): List<DepthValidationItem> {
        require(startAmountUsdt.isFinite() && startAmountUsdt > 0.0)
        require(feeRate == null || (feeRate.isFinite() && feeRate >= 0.0 && feeRate < 1.0))

        val selected = candidates.asSequence()
            .filter { it.path.size == 4 && it.path.first() == "USDT" && it.path.last() == "USDT" }
            .filter { it.instruments.size == 3 && it.instruments.distinct().size == 3 }
            .sortedByDescending { it.grossProfitPct }
            .take(maxCandidates)
            .toList()

        if (selected.isEmpty()) return emptyList()

        val uniqueIds = selected.flatMap { it.instruments }.distinct()
        val semaphore = Semaphore(maxConcurrentRequests)
        val books = coroutineScope {
            uniqueIds.map { id ->
                async {
                    id to runCatching { semaphore.withPermit { loadOrderBook(id) } }
                }
            }.awaitAll().toMap()
        }

        return selected.map { candidate ->
            val missing = candidate.instruments.firstOrNull { id ->
                val result = books[id] ?: return@firstOrNull true
                result.isFailure
            }
            if (missing != null) {
                val reason = books[missing]?.exceptionOrNull()?.message ?: "Order book unavailable"
                return@map DepthValidationItem(
                    candidate, DepthValidationStatus.DEPTH_REJECTED,
                    null, null, null, "$missing: $reason"
                )
            }

            val marketDepths = candidate.instruments.associateWith { id ->
                val book = books.getValue(id).getOrThrow()
                val rule = book.instrumentId
                // Currency orientation is derived from the candidate path and instrument ID.
                val parts = rule.split("-")
                MarketDepth(rule, parts[0], parts[1], book)
            }
            runCatching {
                val result = OrderBookDepthSimulator.simulateCycle(
                    path = candidate.path,
                    marketIds = candidate.instruments,
                    markets = marketDepths,
                    startAmount = startAmountUsdt,
                    feeRate = feeRate ?: 0.0,
                    now = now,
                    maxBookAgeMs = maxBookAgeMs
                )
                DepthValidationItem(
                    candidate = candidate,
                    status = if (feeRate == null) DepthValidationStatus.DEPTH_PASSED_GROSS_ONLY
                        else DepthValidationStatus.DEPTH_PASSED_WITH_FEE,
                    finalAmount = result.finalAmount,
                    grossReturnPct = result.grossReturnPct,
                    netReturnPct = if (feeRate == null) null else result.netReturnPct,
                    message = if (feeRate == null)
                        "Depth passed for 10 USDT; fee tier not supplied, so this is gross-only."
                    else "Depth passed using the supplied fee rate; instrument lot/minimum rules are still pending."
                )
            }.getOrElse { error ->
                DepthValidationItem(
                    candidate, DepthValidationStatus.DEPTH_REJECTED,
                    null, null, null, error.message ?: "Depth validation failed"
                )
            }
        }
    }
}
