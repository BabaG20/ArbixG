package com.arbixg.app.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkxDemoOrderApiTest {
    private val credentials = OkxCredentials("demo-key", "demo-secret", "demo-pass")
    private val timestamp = "2026-01-01T00:00:00.000Z"

    private class RecordingTransport(
        private val response: OkxDemoHttpResponse = OkxDemoHttpResponse(
            200,
            """{"code":"0","msg":"","data":[{"ordId":"987654","clOrdId":"CycleLeg0001","instId":"BTC-USDT","state":"live"}]}"""
        )
    ) : OkxDemoHttpTransport {
        var method: String? = null
        var path: String? = null
        var body: String? = null
        var headers: Map<String, String> = emptyMap()

        override fun execute(
            method: String,
            requestPath: String,
            body: String,
            headers: Map<String, String>
        ): OkxDemoHttpResponse {
            this.method = method
            this.path = requestPath
            this.body = body
            this.headers = headers
            return response
        }
    }

    @Test
    fun placeLimitOrderAlwaysUsesDemoHeaderAndCashLimitFields() = runBlocking {
        val transport = RecordingTransport()
        val api = OkxDemoOrderApi(credentials, { 1_767_225_600_000L }, transport)

        val result = api.placeLimitOrder(
            OkxDemoLimitOrder("BTC-USDT", "buy", "60000.10", "0.001", "CycleLeg0001")
        )

        assertEquals("987654", result.orderId)
        assertEquals("POST", transport.method)
        assertEquals("/api/v5/trade/order", transport.path)
        assertEquals("1", transport.headers["x-simulated-trading"])
        assertEquals("demo-key", transport.headers["OK-ACCESS-KEY"])
        assertEquals(timestamp, transport.headers["OK-ACCESS-TIMESTAMP"])
        val requestBody = JSONObject(transport.body!!)
        assertEquals("BTC-USDT", requestBody.getString("instId"))
        assertEquals("cash", requestBody.getString("tdMode"))
        assertEquals("limit", requestBody.getString("ordType"))
        assertEquals("buy", requestBody.getString("side"))
        assertEquals("60000.10", requestBody.getString("px"))
        assertEquals("0.001", requestBody.getString("sz"))
        assertEquals("CycleLeg0001", requestBody.getString("clOrdId"))
        assertTrue(transport.headers["OK-ACCESS-SIGN"]!!.isNotBlank())
    }

    @Test
    fun queryUsesClientOrderIdAndDemoHeaderForAmbiguousSubmissionRecovery() = runBlocking {
        val transport = RecordingTransport()
        val api = OkxDemoOrderApi(credentials, { 1_767_225_600_000L }, transport)

        api.queryByClientOrderId("BTC-USDT", "CycleLeg0001")

        assertEquals("GET", transport.method)
        assertEquals(
            "/api/v5/trade/order?instId=BTC-USDT&clOrdId=CycleLeg0001",
            transport.path
        )
        assertEquals("1", transport.headers["x-simulated-trading"])
        assertEquals("", transport.body)
    }

    @Test
    fun cancelUsesDemoHeaderAndExactClientOrderIdentity() = runBlocking {
        val transport = RecordingTransport()
        val api = OkxDemoOrderApi(credentials, { 1_767_225_600_000L }, transport)

        api.cancelByClientOrderId("BTC-USDT", "CycleLeg0001")

        assertEquals("POST", transport.method)
        assertEquals("/api/v5/trade/cancel-order", transport.path)
        assertEquals("1", transport.headers["x-simulated-trading"])
        val body = JSONObject(transport.body!!)
        assertEquals("BTC-USDT", body.getString("instId"))
        assertEquals("CycleLeg0001", body.getString("clOrdId"))
    }

    @Test
    fun rejectsInvalidOrderInputsBeforeAnyNetworkRequest() {
        val transport = RecordingTransport()
        val api = OkxDemoOrderApi(credentials, { 1_767_225_600_000L }, transport)

        val invalid = runCatching {
            OkxDemoLimitOrder("BTC-USDT", "buy", "0", "0.001", "CycleLeg0001")
        }
        assertTrue(invalid.isFailure)
        assertEquals(null, transport.method)

        val invalidClientId = runCatching {
            OkxDemoLimitOrder("BTC-USDT", "buy", "10", "1", "cycle-leg-1")
        }
        assertTrue(invalidClientId.isFailure)
    }

    @Test
    fun rejectsMismatchedExchangeOrderIdentity() = runBlocking {
        val transport = RecordingTransport(
            OkxDemoHttpResponse(
                200,
                """{"code":"0","data":[{"ordId":"987654","clOrdId":"OtherCycle","instId":"BTC-USDT","state":"live"}]}"""
            )
        )
        val api = OkxDemoOrderApi(credentials, { 1_767_225_600_000L }, transport)

        val result = runCatching {
            api.queryByClientOrderId("BTC-USDT", "CycleLeg0001")
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsExchangeApiErrorsAndHttpFailures() = runBlocking {
        val apiErrorTransport = RecordingTransport(
            OkxDemoHttpResponse(200, """{"code":"51000","msg":"Parameter error","data":[]}""")
        )
        val apiError = runCatching {
            OkxDemoOrderApi(credentials, { 1_767_225_600_000L }, apiErrorTransport)
                .queryByClientOrderId("BTC-USDT", "CycleLeg0001")
        }
        assertTrue(apiError.isFailure)

        val httpErrorTransport = RecordingTransport(
            OkxDemoHttpResponse(503, """{"code":"50000","msg":"Unavailable","data":[]}""")
        )
        val httpError = runCatching {
            OkxDemoOrderApi(credentials, { 1_767_225_600_000L }, httpErrorTransport)
                .queryByClientOrderId("BTC-USDT", "CycleLeg0001")
        }
        assertTrue(httpError.isFailure)
    }
}
