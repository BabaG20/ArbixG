package com.arbixg.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotOrderBookTest {
    @Test
    fun acceptsFreshSortedNonCrossedBook() {
        val book = SpotOrderBook(
            "BTC-USDT", 10_000L,
            bids = listOf(OrderBookLevel(99.0, 1.0), OrderBookLevel(98.0, 2.0)),
            asks = listOf(OrderBookLevel(100.0, 1.0), OrderBookLevel(101.0, 2.0))
        )
        assertTrue(book.isUsable(now = 10_500L, maxAgeMs = 2_000L))
    }

    @Test
    fun rejectsStaleFutureAndCrossedBooks() {
        val stale = SpotOrderBook("BTC-USDT", 1_000L, listOf(OrderBookLevel(99.0, 1.0)), listOf(OrderBookLevel(100.0, 1.0)))
        val future = stale.copy(timestamp = 12_000L)
        val crossed = stale.copy(bids = listOf(OrderBookLevel(101.0, 1.0)))
        assertFalse(stale.isUsable(now = 5_000L, maxAgeMs = 2_000L))
        assertFalse(future.isUsable(now = 5_000L, maxAgeMs = 2_000L))
        assertFalse(crossed.isUsable(now = 1_000L, maxAgeMs = 2_000L))
    }

    @Test
    fun rejectsUnsortedOrNonFiniteLevels() {
        val unsortedBids = SpotOrderBook(
            "BTC-USDT", 10_000L,
            bids = listOf(OrderBookLevel(98.0, 1.0), OrderBookLevel(99.0, 1.0)),
            asks = listOf(OrderBookLevel(100.0, 1.0))
        )
        val unsortedAsks = SpotOrderBook(
            "BTC-USDT", 10_000L,
            bids = listOf(OrderBookLevel(99.0, 1.0)),
            asks = listOf(OrderBookLevel(101.0, 1.0), OrderBookLevel(100.0, 1.0))
        )
        val invalid = SpotOrderBook(
            "BTC-USDT", 10_000L,
            bids = listOf(OrderBookLevel(Double.NaN, 1.0)),
            asks = listOf(OrderBookLevel(100.0, 1.0))
        )
        assertFalse(unsortedBids.isUsable(now = 10_000L))
        assertFalse(unsortedAsks.isUsable(now = 10_000L))
        assertFalse(invalid.isUsable(now = 10_000L))
    }
}
