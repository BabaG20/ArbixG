package com.arbixg.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class OrderBookLevel(val price: Double, val size: Double)

data class SpotOrderBook(
    val instrumentId: String,
    val timestamp: Long,
    val bids: List<OrderBookLevel>,
    val asks: List<OrderBookLevel>
) {
    fun isUsable(now: Long, maxAgeMs: Long = 2_000L): Boolean {
        if (timestamp <= 0L || timestamp > now + 1_000L || now - timestamp > maxAgeMs) return false
        if (bids.isEmpty() || asks.isEmpty()) return false
        val bestBid = bids.first().price
        val bestAsk = asks.first().price
        return bestBid > 0.0 && bestAsk > 0.0 && bestBid <= bestAsk &&
            bids.all { it.price > 0.0 && it.size > 0.0 } &&
            asks.all { it.price > 0.0 && it.size > 0.0 }
    }

    fun bidDepthBase(): Double = bids.sumOf { it.size }
    fun askDepthBase(): Double = asks.sumOf { it.size }
}

/** Public OKX L2 order-book snapshots. No private credentials or orders are used here. */
class OkxOrderBookRepository(
    private val baseUrl: String = "https://www.okx.com",
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 5_000
) {
    suspend fun load(instId: String, depth: Int = 50): SpotOrderBook = withContext(Dispatchers.IO) {
        require(instId.matches(Regex("[A-Z0-9]+-[A-Z0-9]+"))) { "Invalid OKX instrument ID" }
        require(depth in 1..400) { "Depth must be between 1 and 400" }
        val encodedId = java.net.URLEncoder.encode(instId, Charsets.UTF_8.name())
        val connection = URL("$baseUrl/api/v5/market/books?instId=$encodedId&sz=$depth")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(status in 200..299) { "OKX HTTP $status" }
            val root = JSONObject(body)
            check(root.optString("code") == "0") { "OKX order-book error: ${root.optString("msg", "unknown")}" }
            val data = root.getJSONArray("data")
            check(data.length() > 0) { "OKX returned an empty order book" }
            val item = data.getJSONObject(0)
            SpotOrderBook(
                instrumentId = instId,
                timestamp = item.optString("ts").toLongOrNull() ?: 0L,
                bids = parseLevels(item.getJSONArray("bids")),
                asks = parseLevels(item.getJSONArray("asks"))
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLevels(array: org.json.JSONArray): List<OrderBookLevel> {
        val result = ArrayList<OrderBookLevel>(array.length())
        for (i in 0 until array.length()) {
            val level = array.getJSONArray(i)
            if (level.length() < 2) continue
            val price = level.optString(0).toDoubleOrNull() ?: continue
            val size = level.optString(1).toDoubleOrNull() ?: continue
            if (price.isFinite() && size.isFinite() && price > 0.0 && size > 0.0) {
                result += OrderBookLevel(price, size)
            }
        }
        return result
    }
}
