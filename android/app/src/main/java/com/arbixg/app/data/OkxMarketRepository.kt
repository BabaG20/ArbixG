package com.arbixg.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SpotTicker(
    val instrumentId: String,
    val baseCurrency: String,
    val quoteCurrency: String,
    val last: String,
    val bid: String,
    val ask: String,
    val timestamp: Long
)

data class TriangularCandidate(
    val path: List<String>,
    val instruments: List<String>,
    val grossProfitPct: Double,
    val feeStatus: String
)

data class MarketSnapshot(
    val tickers: List<SpotTicker>,
    val candidates: List<TriangularCandidate>,
    val activeSpotMarkets: Int,
    val exchangeTimestamp: Long
)

class OkxMarketRepository(
    private val baseUrl: String = "https://www.okx.com",
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000
) {
    suspend fun loadAllSpotMarkets(): MarketSnapshot = withContext(Dispatchers.IO) {
        // Fetch the complete live SPOT instrument catalogue and the complete SPOT ticker
        // snapshot, rather than making one request per watchlist symbol.
        val instruments = request("/api/v5/public/instruments?instType=SPOT").getJSONArray("data")
        val tickersJson = request("/api/v5/market/tickers?instType=SPOT").getJSONArray("data")
        val liveRules = HashMap<String, Pair<String, String>>()
        for (i in 0 until instruments.length()) {
            val item = instruments.getJSONObject(i)
            if (item.optString("state") == "live") {
                liveRules[item.optString("instId")] = item.optString("baseCcy") to item.optString("quoteCcy")
            }
        }
        val tickers = ArrayList<SpotTicker>(tickersJson.length())
        for (i in 0 until tickersJson.length()) {
            val item = tickersJson.getJSONObject(i)
            val id = item.optString("instId")
            val currencies = liveRules[id] ?: continue
            val last = item.optString("last")
            val bid = item.optString("bidPx")
            val ask = item.optString("askPx")
            val ts = item.optString("ts").toLongOrNull() ?: continue
            if (!last.toDoubleOrNull().let { it != null && it > 0.0 } ||
                !bid.toDoubleOrNull().let { it != null && it > 0.0 } ||
                !ask.toDoubleOrNull().let { it != null && it > 0.0 } ||
                (bid.toDoubleOrNull() ?: 0.0) > (ask.toDoubleOrNull() ?: 0.0)) continue
            tickers += SpotTicker(id, currencies.first, currencies.second, last, bid, ask, ts)
        }
        MarketSnapshot(
            tickers = tickers,
            candidates = TriangularScanner.scanAll(tickers),
            activeSpotMarkets = tickers.size,
            exchangeTimestamp = tickers.maxOfOrNull { it.timestamp } ?: 0L
        )
    }

    private fun request(path: String): JSONObject {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
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
            if (root.optString("code") != "0") error("OKX API error: ${root.optString("msg", "unknown")}")
            return root
        } finally {
            connection.disconnect()
        }
    }
}
