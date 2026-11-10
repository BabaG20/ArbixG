package com.arbixg.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SpotTicker(
    val instrumentId: String,
    val last: String,
    val bid: String,
    val ask: String,
    val timestamp: Long
)

class OkxMarketRepository(
    private val baseUrl: String = "https://www.okx.com",
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 8_000
) {
    private val watchlist = listOf("BTC-USDT", "ETH-USDT", "SOL-USDT", "PI-USDT")

    suspend fun loadWatchlist(): List<SpotTicker> = withContext(Dispatchers.IO) {
        watchlist.map { instrumentId -> fetchTicker(instrumentId) }
    }

    private fun fetchTicker(instrumentId: String): SpotTicker {
        val encodedId = java.net.URLEncoder.encode(instrumentId, Charsets.UTF_8.name())
        val connection = (URL("$baseUrl/api/v5/market/ticker?instId=$encodedId").openConnection() as HttpURLConnection)
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (status !in 200..299) error("OKX HTTP $status")
            val root = JSONObject(body)
            if (root.optString("code") != "0") {
                error("OKX API error: ${root.optString("msg", "unknown")}")
            }
            val ticker = root.getJSONArray("data").optJSONObject(0)
                ?: error("OKX returned no ticker for $instrumentId")
            val last = ticker.optString("last")
            val bid = ticker.optString("bidPx")
            val ask = ticker.optString("askPx")
            val timestamp = ticker.optString("ts").toLongOrNull()
                ?: error("OKX returned an invalid timestamp for $instrumentId")
            if (!last.toDoubleOrNull().let { it != null && it > 0.0 } ||
                !bid.toDoubleOrNull().let { it != null && it > 0.0 } ||
                !ask.toDoubleOrNull().let { it != null && it > 0.0 }) {
                error("OKX returned invalid prices for $instrumentId")
            }
            return SpotTicker(instrumentId, last, bid, ask, timestamp)
        } finally {
            connection.disconnect()
        }
    }
}
