package com.arbixg.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OkxPrivateApiTest {
    @Test
    fun createsOkxHmacSha256Base64Signature() {
        val signature = OkxPrivateApi.sign(
            timestamp = "2026-01-01T00:00:00.000Z",
            method = "GET",
            requestPath = "/api/v5/account/balance?ccy=USDT",
            body = "",
            secretKey = "Jefe"
        )
        assertEquals("Ux8qnVDAHVEKTDdNCxlC/HdRcH/dZfCz+UkjLDGF32g=", signature)
    }

    @Test
    fun signatureIncludesQueryStringAndHttpMethod() {
        val first = OkxPrivateApi.sign(
            "2026-01-01T00:00:00.000Z", "GET",
            "/api/v5/account/balance?ccy=USDT", "", "secret"
        )
        val changedQuery = OkxPrivateApi.sign(
            "2026-01-01T00:00:00.000Z", "GET",
            "/api/v5/account/balance?ccy=BTC", "", "secret"
        )
        val changedMethod = OkxPrivateApi.sign(
            "2026-01-01T00:00:00.000Z", "POST",
            "/api/v5/account/balance?ccy=USDT", "", "secret"
        )
        assertNotEquals(first, changedQuery)
        assertNotEquals(first, changedMethod)
    }
}
