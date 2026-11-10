package com.arbixg.app.data

import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Strict OKX Spot Demo order adapter.
 *
 * There is deliberately no live-mode parameter, configurable host, or fallback:
 * every request is sent to the OKX REST host with x-simulated-trading: 1.
 * This adapter is not wired to the UI or an automatic trading loop.
 */
data class OkxDemoLimitOrder(
    val instrumentId: String,
    val side: String,
    val price: String,
    val size: String,
    val clientOrderId: String
) {
    init {
        require(instrumentId.matches(Regex("[A-Z0-9-]{3,32}"))) { "Invalid Spot instrument ID." }
        require(side == "buy" || side == "sell") { "Order side must be buy or sell." }
        require(price.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } == true) {
            "Limit price must be a positive decimal."
        }
        require(size.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } == true) {
            "Order size must be a positive decimal."
        }
        require(clientOrderId.matches(Regex("[A-Za-z0-9]{1,32}"))) {
            "Client order ID must contain 1-32 alphanumeric characters."
        }
    }
}

data class OkxDemoOrderResult(
    val orderId: String,
    val clientOrderId: String,
    val instrumentId: String,
    val state: String,
    val raw: JSONObject
)

data class OkxDemoHttpResponse(val statusCode: Int, val body: String)

fun interface OkxDemoHttpTransport {
    fun execute(
        method: String,
        requestPath: String,
        body: String,
        headers: Map<String, String>
    ): OkxDemoHttpResponse
}

private class UrlConnectionOkxDemoTransport(
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int
) : OkxDemoHttpTransport {
    override fun execute(
        method: String,
        requestPath: String,
        body: String,
        headers: Map<String, String>
    ): OkxDemoHttpResponse {
        val connection = URL(OkxDemoOrderApi.REST_BASE_URL + requestPath).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            return OkxDemoHttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }
}

class OkxDemoOrderApi(
    private val credentials: OkxCredentials,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transport: OkxDemoHttpTransport = UrlConnectionOkxDemoTransport(8_000, 12_000)
) {
    suspend fun placeLimitOrder(order: OkxDemoLimitOrder): OkxDemoOrderResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("instId", order.instrumentId)
            .put("tdMode", "cash")
            .put("side", order.side)
            .put("ordType", "limit")
            .put("px", order.price)
            .put("sz", order.size)
            .put("clOrdId", order.clientOrderId)
            .toString()
        val root = request("POST", PLACE_ORDER_PATH, body)
        parseSingleOrder(root, order.instrumentId, order.clientOrderId)
    }

    /** Query by the durable client ID after a timeout or ambiguous submit response. */
    suspend fun queryByClientOrderId(
        instrumentId: String,
        clientOrderId: String
    ): OkxDemoOrderResult = withContext(Dispatchers.IO) {
        requireValidIdentity(instrumentId, clientOrderId)
        val path = "$ORDER_PATH?instId=${encode(instrumentId)}&clOrdId=${encode(clientOrderId)}"
        parseSingleOrder(request("GET", path, ""), instrumentId, clientOrderId)
    }

    /** Cancellation is also Demo-only and must target the exact instrument/client ID. */
    suspend fun cancelByClientOrderId(
        instrumentId: String,
        clientOrderId: String
    ): OkxDemoOrderResult = withContext(Dispatchers.IO) {
        requireValidIdentity(instrumentId, clientOrderId)
        val body = JSONObject()
            .put("instId", instrumentId)
            .put("clOrdId", clientOrderId)
            .toString()
        parseSingleOrder(request("POST", CANCEL_ORDER_PATH, body), instrumentId, clientOrderId)
    }

    private fun request(method: String, path: String, body: String): JSONObject {
        val timestamp = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(clock()))
        val signature = OkxPrivateApi.sign(timestamp, method, path, body, credentials.secretKey)
        val headers = mapOf(
            "Accept" to "application/json",
            "OK-ACCESS-KEY" to credentials.apiKey,
            "OK-ACCESS-SIGN" to signature,
            "OK-ACCESS-TIMESTAMP" to timestamp,
            "OK-ACCESS-PASSPHRASE" to credentials.passphrase,
            "x-simulated-trading" to "1"
        )
        val response = transport.execute(method, path, body, headers)
        if (response.statusCode !in 200..299) {
            error("OKX Demo API HTTP ${response.statusCode}.")
        }
        val root = JSONObject(response.body)
        if (root.optString("code") != "0") {
            error("OKX Demo API rejected the request: ${root.optString("msg", "unknown error")}")
        }
        return root
    }

    private fun parseSingleOrder(
        root: JSONObject,
        expectedInstrumentId: String,
        expectedClientOrderId: String
    ): OkxDemoOrderResult {
        val item = root.optJSONArray("data")?.optJSONObject(0)
            ?: error("OKX Demo response did not contain an order.")
        // Place/cancel acknowledgements may omit instId; retain the request identity only
        // when the response omits it, while rejecting any conflicting non-empty value.
        val instrumentId = item.optString("instId").ifBlank { expectedInstrumentId }
        val clientOrderId = item.optString("clOrdId").ifBlank { expectedClientOrderId }
        val orderId = item.optString("ordId")
        require(instrumentId == expectedInstrumentId && clientOrderId == expectedClientOrderId) {
            "OKX Demo response order identity did not match the request."
        }
        require(orderId.isNotBlank()) { "OKX Demo response omitted the exchange order ID." }
        return OkxDemoOrderResult(
            orderId = orderId,
            clientOrderId = clientOrderId,
            instrumentId = instrumentId,
            state = item.optString("state"),
            raw = item
        )
    }

    private fun requireValidIdentity(instrumentId: String, clientOrderId: String) {
        require(instrumentId.matches(Regex("[A-Z0-9-]{3,32}"))) { "Invalid Spot instrument ID." }
        require(clientOrderId.matches(Regex("[A-Za-z0-9]{1,32}"))) {
            "Client order ID must contain 1-32 alphanumeric characters."
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val REST_BASE_URL = "https://openapi.okx.com"
        private const val PLACE_ORDER_PATH = "/api/v5/trade/order"
        private const val ORDER_PATH = "/api/v5/trade/order"
        private const val CANCEL_ORDER_PATH = "/api/v5/trade/cancel-order"
        private val TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
