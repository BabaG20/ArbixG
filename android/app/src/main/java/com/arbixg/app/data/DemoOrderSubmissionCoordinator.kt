package com.arbixg.app.data

import kotlinx.coroutines.CancellationException

/**
 * Coordinates the durable pre-submit intent and the strict Demo-only API.
 *
 * A client ID is never submitted twice by this coordinator. If the result is
 * ambiguous, the intent is left recoverable and the caller must query OKX by
 * that client ID; it must not blindly retry the POST.
 */
class DemoOrderSubmissionCoordinator(
    private val journal: OrderIntentDao,
    private val demoApi: OkxDemoOrderGateway,
    private val clock: () -> Long = System::currentTimeMillis
) {
    sealed interface Result {
        data class Acknowledged(val order: OkxDemoOrderResult) : Result
        data class AlreadyExists(val intent: OrderIntentEntity) : Result
        data class Ambiguous(val clientOrderId: String, val reason: String) : Result
        data class NotSubmitted(val reason: String) : Result
    }

    suspend fun submitOnce(intent: OrderIntentEntity): Result {
        require(intent.state == OrderIntentEntity.PREPARED) {
            "New submissions must start from a PREPARED durable intent."
        }

        val inserted = journal.insertIfAbsent(intent)
        val persisted = if (inserted >= 0L) intent else journal.get(intent.clientOrderId)
            ?: return Result.NotSubmitted("An order intent with this cycle/leg already exists.")

        if (persisted.clientOrderId != intent.clientOrderId ||
            persisted.cycleId != intent.cycleId ||
            persisted.legIndex != intent.legIndex ||
            persisted.instrumentId != intent.instrumentId ||
            persisted.side != intent.side ||
            persisted.orderType != intent.orderType ||
            persisted.price != intent.price ||
            persisted.size != intent.size
        ) {
            return Result.NotSubmitted("Existing order intent does not match this request.")
        }

        // A PREPARED intent is safe to resume: no network request is allowed until
        // the durable transition to SUBMITTING wins. Only one concurrent caller can win.
        if (inserted < 0L && persisted.state != OrderIntentEntity.PREPARED) {
            return Result.AlreadyExists(persisted)
        }
        if (!journal.markSubmitting(intent.clientOrderId, clock())) {
            return Result.AlreadyExists(
                journal.get(intent.clientOrderId) ?: persisted
            )
        }

        return try {
            val order = demoApi.placeLimitOrder(
                OkxDemoLimitOrder(
                    instrumentId = intent.instrumentId,
                    side = intent.side,
                    price = intent.price,
                    size = intent.size,
                    clientOrderId = intent.clientOrderId
                )
            )
            // If this local write fails, SUBMITTING remains recoverable after restart.
            val saved = journal.markAcknowledged(intent.clientOrderId, order.orderId, clock())
            if (saved) Result.Acknowledged(order)
            else Result.Ambiguous(intent.clientOrderId, "JournalAcknowledgementNotPersisted")
        } catch (cancelled: CancellationException) {
            // Leave SUBMITTING durable; startup recovery must query OKX before any further action.
            throw cancelled
        } catch (failure: Exception) {
            // Do not retry: a timeout may mean OKX accepted the order.
            val safeReason = failure::class.java.simpleName.ifBlank { "Unknown network/API failure" }
            journal.markUnknown(intent.clientOrderId, clock(), safeReason)
            Result.Ambiguous(intent.clientOrderId, safeReason)
        }
    }

    /** Query the exchange for an ambiguous intent; never creates or resubmits an order. */
    suspend fun reconcileUnknown(intent: OrderIntentEntity): Result {
        require(intent.state == OrderIntentEntity.UNKNOWN ||
            intent.state == OrderIntentEntity.SUBMITTING ||
            intent.state == OrderIntentEntity.ACKNOWLEDGED
        ) { "Only recoverable intents can be reconciled." }

        return try {
            val order = demoApi.queryByClientOrderId(intent.instrumentId, intent.clientOrderId)
            val saved = journal.markAcknowledged(intent.clientOrderId, order.orderId, clock())
            if (saved) Result.Acknowledged(order)
            else Result.Ambiguous(intent.clientOrderId, "JournalAcknowledgementNotPersisted")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val safeReason = failure::class.java.simpleName.ifBlank { "Unknown lookup failure" }
            Result.Ambiguous(intent.clientOrderId, safeReason)
        }
    }
}
