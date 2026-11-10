package com.arbixg.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SpotInstrumentRules(
    val instrumentId: String,
    val baseCurrency: String,
    val quoteCurrency: String,
    val lotSize: String,
    val minimumSize: String,
    val tickSize: String,
    val state: String
)

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
    val exchangeTimestamp: Long,
    val staleTickerCount: Int,
    val instrumentRules: Map<String, SpotInstrumentRules>
)

class OkxMarketRepository(
    private val baseUrl: String = "https://www.okx.com",
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000,
    private val maxTickerAgeMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun loadAllSpotMarkets(): MarketSnapshot = withContext(Dispatchers.IO) {
        // One complete instrument catalogue + one complete ticker snapshot; no hardcoded watchlist.
        val instruments = request("/api/v5/public/instruments?instType=SPOT").getJSONArray("data")
        val tickersJson = request("/api/v5/market/tickers?instType=SPOT").getJSONArray("data")
        val rules = LinkedHashMap<String, SpotInstrumentRules>()
        for (i in 0 until instruments.length()) {
            val item = instruments.getJSONObject(i)
            if (item.optString("state") != "live") continue
            val id = item.optString("instId")
            if (id.isBlank()) continue
            rules[id] = SpotInstrumentRules(
                instrumentId = id,
                baseCurrency = item.optString("baseCcy"),
                quoteCurrency = item.optString("quoteCcy"),
                lotSize = item.optString("lotSz"),
                minimumSize = item.optString("minSz"),
                tickSize = item.optString("tickSz"),
                state = item.optString("state")
            )
        }

        val now = clock()
        val tickers = ArrayList<SpotTicker>(tickersJson.length())
        var staleCount = 0
        for (i in 0 until tickersJson.length()) {
            val item = tickersJson.getJSONObject(i)
            val id = item.optString("instId")
            val rule = rules[id] ?: continue
            val last = item.optString("last")
            val bid = item.optString("bidPx")
            val ask = item.optString("askPx")
            val ts = item.optString("ts").toLongOrNull() ?: continue
            val lastValue = last.toDoubleOrNull()
            val bidValue = bid.toDoubleOrNull()
            val askValue = ask.toDoubleOrNull()
            if (lastValue == null || bidValue == null || askValue == null ||
                !lastValue.isFinite() || !bidValue.isFinite() || !askValue.isFinite() ||
                lastValue <= 0.0 || bidValue <= 0.0 || askValue <= 0.0 || bidValue > askValue) continue
            if (ts <= 0L || ts > now + 5_000L || now - ts > maxTickerAgeMs) {
                staleCount++
                continue
            }
            tickers += SpotTicker(
                instrumentId = id,
                baseCurrency = rule.baseCurrency,
                quoteCurrency = rule.quoteCurrency,
                last = last,
                bid = bid,
                ask = ask,
                timestamp = ts
            )
        }
        MarketSnapshot(
            tickers = tickers,
            candidates = TriangularScanner.scanAll(tickers),
            activeSpotMarkets = rules.size,
            exchangeTimestamp = tickers.maxOfOrNull { it.timestamp } ?: 0L,
            staleTickerCount = staleCount,
            instrumentRules = rules
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
