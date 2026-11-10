package com.arbixg.app.data

/** Persistence boundary for exchange order reconciliation. */
class OrderJournal(private val dao: TrackedOrderDao) {
    suspend fun getRecent(): List<ReconciledOrder> =
        dao.getRecent().map { it.toReconciledOrder() }

    suspend fun get(orderId: String): ReconciledOrder? =
        dao.getByOrderId(orderId)?.toReconciledOrder()

    suspend fun save(order: ReconciledOrder) {
        dao.upsert(order.toEntity())
    }

    suspend fun saveAll(orders: List<ReconciledOrder>) {
        if (orders.isNotEmpty()) dao.upsertAll(orders.map { it.toEntity() })
    }
}
