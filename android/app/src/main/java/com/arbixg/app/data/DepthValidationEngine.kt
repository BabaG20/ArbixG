package com.arbixg.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class DepthValidationStatus {
    DEPTH_PASSED_GROSS_ONLY,
    DEPTH_PASSED_WITH_FEE,
    DEPTH_PASSED_WITH_RULES_GROSS_ONLY,
    DEPTH_PASSED_WITH_RULES_AND_FEE,
    DEPTH_REJECTED
}

data class DepthValidationItem(
    val candidate: TriangularCandidate,
    val status: DepthValidationStatus,
    val finalAmount: Double?,
    val grossReturnPct: Double?,
    val netReturnPct: Double?,
    val message: String,
    val dustSummary: String? = null,
    val feeModel: String? = null,
    val slippageBufferBps: Double = 0.0
)

/**
 * Bounded public L2 validation for USDT-starting candidates.
 * Each unique instrument is downloaded once per batch, with at most four
 * concurrent requests. No credentials or orders are used.
 */
class DepthValidationEngine(
    private val loadOrderBook: suspend (String) -> SpotOrderBook,
    private val maxConcurrentRequests: Int = 4,
    private val maxCandidates: Int = Int.MAX_VALUE
) {
    init {
        require(maxConcurrentRequests in 1..8)
        require(maxCandidates > 0)
    }

    suspend fun validateUsdtCandidates(
        candidates: List<TriangularCandidate>,
        startAmountUsdt: Double = 10.0,
        feeRate: Double? = null,
        feeRatesByInstrument: Map<String, Double>? = null,
        feeType: Int? = null,
        now: Long = System.currentTimeMillis(),
        maxBookAgeMs: Long = 2_000L,
        slippageBufferBps: Double = 10.0,
        instrumentRules: Map<String, SpotInstrumentRules> = emptyMap()
    ): List<DepthValidationItem> {
        require(startAmountUsdt.isFinite() && startAmountUsdt > 0.0)
        require(slippageBufferBps.isFinite() && slippageBufferBps in 0.0..500.0) { "Slippage buffer must be between 0 and 500 bps." }
        require(feeRate == null || (feeRate.isFinite() && feeRate >= 0.0 && feeRate < 1.0))
        feeRatesByInstrument?.values?.forEach { rate ->
            require(rate.isFinite() && rate >= 0.0 && rate < 1.0) { "Each instrument fee rate must be between 0 and 1." }
        }
        require(feeType == null || feeType in 0..1) { "OKX feeType must be 0 or 1." }

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
                val adverseBook = if (slippageBufferBps == 0.0) book else book.copy(
                    bids = book.bids.map { it.copy(price = it.price * (1.0 - slippageBufferBps / 10_000.0)) },
                    asks = book.asks.map { it.copy(price = it.price * (1.0 + slippageBufferBps / 10_000.0)) }
                )
                MarketDepth(rule, parts[0], parts[1], adverseBook)
            }
            runCatching {
                val candidateFeeRates = feeRatesByInstrument
                    ?.filterKeys { it in candidate.instruments }
                    ?.takeIf { feeType != null && feeType in 0..1 && candidate.instruments.all(it::containsKey) }
                val useAccountFees = candidateFeeRates != null
                val useLegacyFee = !useAccountFees && feeRate != null
                val result = OrderBookDepthSimulator.simulateCycle(
                    path = candidate.path,
                    marketIds = candidate.instruments,
                    markets = marketDepths,
                    startAmount = startAmountUsdt,
                    feeRate = if (useAccountFees) 0.0 else (feeRate ?: 0.0),
                    feeRatesByInstrument = candidateFeeRates,
                    feeType = if (useAccountFees) feeType!! else 0,
                    now = now,
                    maxBookAgeMs = maxBookAgeMs,
                    instrumentRules = instrumentRules
                )
                val rulesCheck = InstrumentRulesValidator.validate(
                    path = candidate.path,
                    instrumentIds = candidate.instruments,
                    books = candidate.instruments.associateWith { books.getValue(it).getOrThrow() },
                    rules = instrumentRules,
                    legs = result.legs
                )
                if (!rulesCheck.passed) {
                    DepthValidationItem(
                        candidate, DepthValidationStatus.DEPTH_REJECTED,
                        null, null, null, rulesCheck.message
                    )
                } else {
                    DepthValidationItem(
                        candidate = candidate,
                        status = if (!useAccountFees && !useLegacyFee) DepthValidationStatus.DEPTH_PASSED_WITH_RULES_GROSS_ONLY
                            else DepthValidationStatus.DEPTH_PASSED_WITH_RULES_AND_FEE,
                        finalAmount = result.finalAmount,
                        grossReturnPct = result.grossReturnPct,
                        netReturnPct = if (useAccountFees || useLegacyFee) result.netReturnPct else null,
                        message = rulesCheck.message + " Executable depth was stressed by ${slippageBufferBps} bps adverse slippage per leg; gross/net returns include this buffer.",
                        slippageBufferBps = slippageBufferBps,
                        feeModel = when {
                            useAccountFees -> "OKX per-instrument taker rates; account feeType=$feeType; adverse slippage buffer=${slippageBufferBps}bps per leg"
                            useLegacyFee -> "Uniform supplied fee rate; not account-verified; adverse slippage buffer=${slippageBufferBps}bps per leg"
                            else -> null
                        },
                        dustSummary = result.legs.filter { it.dustAmount > 0.0 }
                            .joinToString(" · ") { "${it.instrumentId}: ${String.format("%.8f", it.dustAmount)} ${it.dustCurrency}" }
                            .ifBlank { "No residual dust at the simulated precision." }
                    )
                }
            }.getOrElse { error ->
                DepthValidationItem(
                    candidate, DepthValidationStatus.DEPTH_REJECTED,
                    null, null, null, error.message ?: "Depth validation failed"
                )
            }
        }.sortedWith(
            compareByDescending<DepthValidationItem> { it.netReturnPct != null }
                .thenByDescending { it.netReturnPct ?: it.grossReturnPct ?: Double.NEGATIVE_INFINITY }
        )
    }
}
