package com.arbixg.app.data

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.delay

data class DemoCycleLegSpec(
    val instrumentId: String,
    val side: String,
    val fromCurrency: String,
    val toCurrency: String,
    val limitPrice: BigDecimal,
    val lotSize: BigDecimal,
    val minimumSize: BigDecimal,
    val minimumNotional: BigDecimal?,
    val tickSize: BigDecimal
)

data class DemoThreeLegCycleRequest(
    val cycleId: String,
    val startCurrency: String,
    val startAmount: BigDecimal,
    val legs: List<DemoCycleLegSpec>,
    val requiredFunds: Map<String, BigDecimal>,
    val marketSnapshotEpochMs: Long,
    val estimatedSlippageBps: BigDecimal
) {
    init {
        require(cycleId.isNotBlank() && startCurrency.isNotBlank())
        require(startAmount > BigDecimal.ZERO)
        require(legs.size == 3) { "A triangular cycle must contain exactly three legs." }
        require(legs.first().fromCurrency == startCurrency)
        require(legs[0].toCurrency == legs[1].fromCurrency)
        require(legs[1].toCurrency == legs[2].fromCurrency)
        require(legs[2].toCurrency == startCurrency)
    }
}

sealed interface DemoThreeLegCycleResult {
    data class Completed(val cycleId: String, val finalAmount: BigDecimal, val realizedPnl: BigDecimal, val legOrders: List<ReconciledOrder>) : DemoThreeLegCycleResult
    data class Stopped(val cycleId: String, val legIndex: Int, val reason: String, val legOrders: List<ReconciledOrder>) : DemoThreeLegCycleResult
    data class Ambiguous(val cycleId: String, val legIndex: Int, val clientOrderId: String, val reason: String) : DemoThreeLegCycleResult
    data class Rejected(val cycleId: String, val reason: String) : DemoThreeLegCycleResult
}

/**
 * Demo-only sequential runner. A later leg requires a confirmed FILLED state,
 * fee-adjusted proceeds, and a fresh exchange available-balance check.
 * Partial orders are canceled and reconciled; the cycle stops without guessing
 * an unwind price. Unresolved reservations remain locked for manual recovery.
 */
class DemoThreeLegCycleEngine(
    private val database: ArbixDatabase,
    private val demoApi: OkxDemoOrderApi,
    private val riskManager: PersistentCycleRiskManager,
    private val loadAvailableBalances: suspend (Set<String>) -> Map<String, RiskBalance>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = 750L,
    private val maxPolls: Int = 20
) {
    init {
        require(pollIntervalMs >= 100L)
        require(maxPolls in 1..120)
    }

    suspend fun execute(request: DemoThreeLegCycleRequest): DemoThreeLegCycleResult {
        val balances = try {
            loadAvailableBalances(request.requiredFunds.keys)
        } catch (failure: Exception) {
            return DemoThreeLegCycleResult.Rejected(request.cycleId, safeReason(failure))
        }
        val risk = riskManager.reserve(
            cycleId = request.cycleId,
            startCurrency = request.startCurrency,
            startAmount = request.startAmount,
            requiredFunds = request.requiredFunds,
            balances = balances,
            marketSnapshotEpochMs = request.marketSnapshotEpochMs,
            estimatedSlippageBps = request.estimatedSlippageBps
        )
        if (!risk.approved) return DemoThreeLegCycleResult.Rejected(request.cycleId, risk.reason)

        val completedOrders = ArrayList<ReconciledOrder>(3)
        var submittedAnyOrder = false
        var availableInput = request.startAmount
        var activeLegIndex = 0

        try {
            request.legs.forEachIndexed { index, leg ->
                activeLegIndex = index
                if (riskManager.isKillSwitchEnabled()) {
                    throw CycleStop(index, "Kill switch is enabled; no further legs will be submitted.")
                }
                if (index > 0) {
                    val currentBalances = loadAvailableBalances(setOf(leg.fromCurrency))
                    val balance = currentBalances[leg.fromCurrency]
                        ?: throw CycleStop(index, "Fresh available balance for " + leg.fromCurrency + " is missing.")
                    if (clock() - balance.retrievedAtEpochMs !in 0L..60_000L) {
                        throw CycleStop(index, "Available " + leg.fromCurrency + " balance is stale.")
                    }
                    availableInput = minOf(availableInput, balance.available)
                }

                val sized = CycleOrderSizer.size(
                    instrumentId = leg.instrumentId,
                    side = leg.side,
                    inputCurrency = leg.fromCurrency,
                    availableInput = availableInput,
                    limitPrice = leg.limitPrice,
                    lotSize = leg.lotSize,
                    minimumSize = leg.minimumSize,
                    minimumNotional = leg.minimumNotional,
                    tickSize = leg.tickSize
                )
                val clientId = clientOrderId(request.cycleId, index)
                val now = clock()
                val intent = OrderIntentEntity(
                    clientOrderId = clientId,
                    cycleId = request.cycleId,
                    legIndex = index,
                    instrumentId = leg.instrumentId,
                    side = leg.side,
                    orderType = "limit",
                    price = sized.limitPrice.toPlainString(),
                    size = sized.orderSizeBase.toPlainString(),
                    state = OrderIntentEntity.PREPARED,
                    exchangeOrderId = null,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    lastError = null
                )

                val submission = submitOrRecover(intent)
                val acknowledged = when (submission) {
                    is DemoOrderSubmissionCoordinator.Result.Acknowledged -> submission.order
                    is DemoOrderSubmissionCoordinator.Result.Ambiguous -> {
                        database.tradeRecordDao().insert(
                            TradeRecord(
                                mode = "DEMO",
                                route = request.legs.joinToString(" → ") { it.instrumentId },
                                status = "AMBIGUOUS_AT_LEG_" + (index + 1),
                                createdAtEpochMs = clock(),
                                realizedPnl = null
                            )
                        )
                        return DemoThreeLegCycleResult.Ambiguous(request.cycleId, index, submission.clientOrderId, submission.reason)
                    }
                    is DemoOrderSubmissionCoordinator.Result.AlreadyExists ->
                        throw CycleStop(index, "Existing intent state " + submission.intent.state + "; reconcile it before starting another cycle.")
                    is DemoOrderSubmissionCoordinator.Result.NotSubmitted ->
                        throw CycleStop(index, submission.reason)
                }
                submittedAnyOrder = true
                val reconciled = awaitTerminalFill(index, leg.instrumentId, clientId)
                if (reconciled.status != ReconciledOrderStatus.FILLED) {
                    throw CycleStop(index, "Leg " + (index + 1) + " ended as " + reconciled.status + "; later legs were not submitted.")
                }
                completedOrders += reconciled
                database.orderIntentDao().transition(
                    clientId,
                    OrderIntentEntity.ACKNOWLEDGED,
                    OrderIntentEntity.RECONCILED,
                    acknowledged.orderId,
                    clock(),
                    null
                )
                val movement = CycleFillMath.movement(reconciled, leg.side, leg.fromCurrency, leg.toCurrency)
                availableInput = movement.outputReceived
            }

            val finalAmount = availableInput
            val pnl = finalAmount.subtract(request.startAmount)
            database.tradeRecordDao().insert(
                TradeRecord(
                    mode = "DEMO",
                    route = request.legs.joinToString(" → ") { it.instrumentId },
                    status = "COMPLETED",
                    createdAtEpochMs = clock(),
                    realizedPnl = pnl.toDouble()
                )
            )
            riskManager.release(request.cycleId)
            return DemoThreeLegCycleResult.Completed(request.cycleId, finalAmount, pnl, completedOrders)
        } catch (stopped: CycleStop) {
            database.tradeRecordDao().insert(
                TradeRecord(
                    mode = "DEMO",
                    route = request.legs.joinToString(" → ") { it.instrumentId },
                    status = "STOPPED_AT_LEG_" + (stopped.legIndex + 1),
                    createdAtEpochMs = clock(),
                    realizedPnl = null
                )
            )
            if (!submittedAnyOrder) riskManager.release(request.cycleId)
            return DemoThreeLegCycleResult.Stopped(request.cycleId, stopped.legIndex, stopped.message.orEmpty(), completedOrders)
        } catch (failure: Exception) {
            if (!submittedAnyOrder) riskManager.release(request.cycleId)
            return DemoThreeLegCycleResult.Stopped(request.cycleId, activeLegIndex, safeReason(failure), completedOrders)
        }
    }

    private suspend fun submitOrRecover(intent: OrderIntentEntity): DemoOrderSubmissionCoordinator.Result {
        val journal = database.orderIntentDao()
        val existing = journal.get(intent.clientOrderId)
        if (existing == null || existing.state == OrderIntentEntity.PREPARED) {
            return DemoOrderSubmissionCoordinator(journal, demoApi, clock).submitOnce(intent)
        }
        return when (existing.state) {
            OrderIntentEntity.UNKNOWN, OrderIntentEntity.SUBMITTING ->
                DemoOrderSubmissionCoordinator(journal, demoApi, clock).reconcileUnknown(existing)
            OrderIntentEntity.ACKNOWLEDGED -> try {
                DemoOrderSubmissionCoordinator.Result.Acknowledged(
                    demoApi.queryByClientOrderId(existing.instrumentId, existing.clientOrderId)
                )
            } catch (failure: Exception) {
                DemoOrderSubmissionCoordinator.Result.Ambiguous(existing.clientOrderId, safeReason(failure))
            }
            else -> DemoOrderSubmissionCoordinator.Result.AlreadyExists(existing)
        }
    }

    private suspend fun awaitTerminalFill(
        legIndex: Int,
        instrumentId: String,
        clientOrderId: String
    ): ReconciledOrder {
        var latest: ReconciledOrder? = null
        repeat(maxPolls) {
            val snapshot = demoApi.queryByClientOrderId(instrumentId, clientOrderId).toOrderSnapshot()
            latest = OrderFillReconciler.reconcile(latest, snapshot)
            database.trackedOrderDao().upsert(latest!!.toEntity(clock()))
            when (latest!!.status) {
                ReconciledOrderStatus.FILLED,
                ReconciledOrderStatus.CANCELED,
                ReconciledOrderStatus.REJECTED -> return latest!!
                ReconciledOrderStatus.OPEN,
                ReconciledOrderStatus.PARTIALLY_FILLED -> delay(pollIntervalMs)
            }
        }

        demoApi.cancelByClientOrderId(instrumentId, clientOrderId)
        repeat(maxPolls) {
            val snapshot = demoApi.queryByClientOrderId(instrumentId, clientOrderId).toOrderSnapshot()
            latest = OrderFillReconciler.reconcile(latest, snapshot)
            database.trackedOrderDao().upsert(latest!!.toEntity(clock()))
            when (latest!!.status) {
                ReconciledOrderStatus.CANCELED,
                ReconciledOrderStatus.REJECTED,
                ReconciledOrderStatus.FILLED -> return latest!!
                ReconciledOrderStatus.OPEN,
                ReconciledOrderStatus.PARTIALLY_FILLED -> delay(pollIntervalMs)
            }
        }
        throw CycleStop(legIndex, "Order remains non-terminal after cancellation; reconcile before continuing.")
    }

    private fun clientOrderId(cycleId: String, legIndex: Int): String {
        // Stable across app restarts: a retried cycle/leg resolves to the same OKX client ID.
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((cycleId + ":" + legIndex).toByteArray(StandardCharsets.UTF_8))
        return bytes.take(16).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun safeReason(failure: Exception): String =
        failure.message?.take(180)?.takeIf { it.isNotBlank() } ?: failure::class.java.simpleName

    private class CycleStop(val legIndex: Int, message: String) : Exception(message)
}
