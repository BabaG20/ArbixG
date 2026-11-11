package com.arbixg.app.data

import java.util.Base64
import com.arbixg.app.security.ApiSecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class OkxCredentials(
    val apiKey: String,
    val secretKey: String,
    val passphrase: String
) {
    init {
        require(apiKey.isNotBlank() && secretKey.isNotBlank() && passphrase.isNotBlank())
    }
}

data class OkxAccountBalance(
    val currency: String,
    val available: String,
    val cash: String,
    val frozen: String
)

data class OkxSpotFee(
    val instrumentId: String,
    val groupId: String,
    val makerRate: String,
    val takerRate: String,
    val level: String?
)

data class OkxAccountSnapshot(
    val uid: String?,
    val accountLevel: String?,
    val balances: List<OkxAccountBalance>,
    val fees: List<OkxSpotFee>,
    val retrievedAtEpochMs: Long,
    val demoMode: Boolean,
    /** OKX account fee charging mode: 0=received currency, 1=Spot quote currency. */
    val feeType: String?
)

/**
 * Read-only OKX private REST client. It intentionally exposes no order-placement method.
 * Demo mode adds OKX's simulated-trading header and remains the default.
 */
class OkxPrivateApi(
    private val credentials: OkxCredentials,
    private val baseUrl: String = "https://www.okx.com",
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000
) {
    suspend fun loadAccountSnapshot(instrumentIds: List<String>, demoMode: Boolean): OkxAccountSnapshot =
        withContext(Dispatchers.IO) {
            require(instrumentIds.isNotEmpty()) { "At least one instrument is required to load fee tiers." }
            val config = get("/api/v5/account/config", demoMode).getJSONArray("data")
                .optJSONObject(0) ?: error("OKX account configuration response is empty.")
            val balanceRoot = get("/api/v5/account/balance", demoMode)
            val balanceData = balanceRoot.getJSONArray("data")
            val balances = ArrayList<OkxAccountBalance>()
            if (balanceData.length() > 0) {
                val details = balanceData.getJSONObject(0).optJSONArray("details")
                if (details != null) {
                    for (i in 0 until details.length()) {
                        val item = details.getJSONObject(i)
                        balances += OkxAccountBalance(
                            currency = item.optString("ccy"),
                            available = item.optString("availBal", "0"),
                            cash = item.optString("cashBal", "0"),
                            frozen = item.optString("frozenBal", "0")
                        )
                    }
                }
            }
            // OKX's account instrument endpoint supplies the per-symbol fee group.
            // The fee-rate endpoint accepts one instId per request; its feeGroup array
            // must be matched to that instrument's groupId (top-level legacy rates are deprecated).
            val requestedIds = instrumentIds.distinct()
            val instrumentData = get("/api/v5/account/instruments?instType=SPOT", demoMode).getJSONArray("data")
            val groupsByInstrument = LinkedHashMap<String, String>()
            for (i in 0 until instrumentData.length()) {
                val item = instrumentData.getJSONObject(i)
                val id = item.optString("instId")
                val groupId = item.optString("groupId")
                if (id.isNotBlank() && groupId.isNotBlank()) groupsByInstrument[id] = groupId
            }
            val missingGroups = requestedIds.filterNot { groupsByInstrument.containsKey(it) }
            require(missingGroups.isEmpty()) {
                "OKX account instrument fee group missing for: ${missingGroups.joinToString()}"
            }

            val feesById = LinkedHashMap<String, OkxSpotFee>()
            requestedIds.forEachIndexed { index, instrumentId ->
                // Endpoint limit: 5 requests per 2 seconds per user.
                if (index > 0) delay(450L)
                val feeData = get(
                    "/api/v5/account/trade-fee?instType=SPOT&instId=$instrumentId",
                    demoMode
                ).getJSONArray("data")
                val item = feeData.optJSONObject(0)
                    ?: error("OKX fee response is empty for $instrumentId.")
                val expectedGroupId = groupsByInstrument.getValue(instrumentId)
                val feeGroups = item.optJSONArray("feeGroup")
                    ?: error("OKX fee response omitted feeGroup for $instrumentId.")
                val matchingGroup = (0 until feeGroups.length())
                    .map { feeGroups.getJSONObject(it) }
                    .firstOrNull { it.optString("groupId") == expectedGroupId }
                    ?: error("OKX fee response has no group $expectedGroupId for $instrumentId.")
                val maker = matchingGroup.optString("maker")
                val taker = matchingGroup.optString("taker")
                require(maker.isNotBlank() && taker.isNotBlank()) {
                    "OKX maker/taker fee rate missing for $instrumentId (group $expectedGroupId)."
                }
                feesById[instrumentId] = OkxSpotFee(
                    instrumentId = instrumentId,
                    groupId = expectedGroupId,
                    makerRate = maker,
                    takerRate = taker,
                    level = item.optString("level").takeIf { it.isNotBlank() }
                )
            }
            val fees = requestedIds.map { feesById.getValue(it) }
            OkxAccountSnapshot(
                uid = config.optString("uid").takeIf { it.isNotBlank() },
                accountLevel = config.optString("acctLv").takeIf { it.isNotBlank() },
                balances = balances,
                fees = fees,
                retrievedAtEpochMs = clock(),
                demoMode = demoMode,
                feeType = config.optString("feeType").takeIf { it == "0" || it == "1" }
            )
        }


    /**
     * Reads currently allocated Earn assets. This method is read-only and uses
     * OKX's current Simple Earn, On-chain Earn, ETH/SOL staking and OKUSD
     * balance endpoints. Any endpoint failure is propagated so callers cannot
     * silently treat an incomplete Earn snapshot as empty.
     */
    suspend fun loadEarnHoldings(demoMode: Boolean): List<OkxEarnHolding> = withContext(Dispatchers.IO) {
        val holdings = ArrayList<OkxEarnHolding>()
        holdings += OkxEarnResponseParser.parseSimpleEarn(
            get("/api/v5/finance/savings/balance", demoMode).getJSONArray("data")
        )
        holdings += OkxEarnResponseParser.parseOnChainActive(
            get("/api/v5/finance/staking-defi/orders-active?protocolType=defi", demoMode).getJSONArray("data")
        )
        holdings += OkxEarnResponseParser.parseEthStaking(
            get("/api/v5/finance/staking-defi/eth/balance", demoMode).getJSONArray("data")
        )
        holdings += OkxEarnResponseParser.parseSolStaking(
            get("/api/v5/finance/staking-defi/sol/balance", demoMode).getJSONArray("data")
        )
        holdings += OkxEarnResponseParser.parseStableRewards(
            get("/api/v5/finance/stable-rewards/balance", demoMode).getJSONArray("data")
        )
        holdings += OkxEarnResponseParser.parseOkusd(
            get("/api/v5/finance/okusd/account", demoMode).getJSONArray("data")
        )
        // Dual Investment order history is rate-limited to one request per second.
        var firstDualRequest = true
        listOf("initial", "live", "pending_settle", "pending_redeem").forEach { state ->
            var endId: String? = null
            var page = 0
            while (true) {
                if (!firstDualRequest) delay(1_000L)
                firstDualRequest = false
                val cursor = endId?.let { "&endId=$it" }.orEmpty()
                val data = get(
                    "/api/v5/finance/sfp/dcd/order-history?state=$state&limit=100$cursor",
                    demoMode
                ).getJSONArray("data")
                holdings += OkxEarnResponseParser.parseDualInvestment(data)
                page++
                if (data.length() < 100) break
                val nextEndId = data.optJSONObject(data.length() - 1)?.optString("ordId").orEmpty()
                require(nextEndId.isNotBlank() && nextEndId != endId) {
                    "Dual Investment pagination did not advance; Earn snapshot is incomplete."
                }
                endId = nextEndId
                require(page < 1_000) { "Dual Investment history exceeded the safety pagination limit." }
            }
        }
        holdings
    }

    /** Read-only list of current Spot orders (up to OKX's requested page size). */
    suspend fun loadPendingSpotOrders(demoMode: Boolean): List<OkxOrderSnapshot> = withContext(Dispatchers.IO) {
        val data = get("/api/v5/trade/orders-pending?instType=SPOT&limit=100", demoMode).getJSONArray("data")
        buildList {
            for (i in 0 until data.length()) add(parseOrder(data.getJSONObject(i)))
        }
    }

    /** Read-only refresh for one known order ID, including terminal order states. */
    suspend fun loadOrderSnapshot(
        instrumentId: String,
        orderId: String,
        demoMode: Boolean
    ): OkxOrderSnapshot = withContext(Dispatchers.IO) {
        require(instrumentId.isNotBlank() && orderId.isNotBlank())
        val path = "/api/v5/trade/order?instId=$instrumentId&ordId=$orderId"
        val item = get(path, demoMode).getJSONArray("data").optJSONObject(0)
            ?: error("OKX order query returned no order.")
        parseOrder(item)
    }

    private fun parseOrder(item: JSONObject): OkxOrderSnapshot = OkxOrderSnapshot(
        orderId = item.optString("ordId"),
        instrumentId = item.optString("instId"),
        exchangeState = item.optString("state"),
        requestedSize = item.optString("sz"),
        cumulativeFilledSize = item.optString("accFillSz"),
        averageFillPrice = item.optString("avgPx"),
        cumulativeFee = item.optString("fee", "0"),
        feeCurrency = item.optString("feeCcy").takeIf { it.isNotBlank() },
        updateTimeEpochMs = item.optString("uTime").toLongOrNull() ?: 0L
    )

    private fun get(requestPath: String, demoMode: Boolean): JSONObject {
        val timestamp = TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(clock()))
        val signature = sign(timestamp, "GET", requestPath, "", credentials.secretKey)
        val connection = URL(baseUrl + requestPath).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("OK-ACCESS-KEY", credentials.apiKey)
        connection.setRequestProperty("OK-ACCESS-SIGN", signature)
        connection.setRequestProperty("OK-ACCESS-TIMESTAMP", timestamp)
        connection.setRequestProperty("OK-ACCESS-PASSPHRASE", credentials.passphrase)
        if (demoMode) connection.setRequestProperty("x-simulated-trading", "1")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("OKX private API HTTP $status.")
            val root = JSONObject(body)
            if (root.optString("code") != "0") {
                val message = root.optString("msg", "unknown error")
                error("OKX private API rejected the request: $message")
            }
            return root
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** OKX prehash = timestamp + method + requestPath (including query) + body. */
        fun sign(timestamp: String, method: String, requestPath: String, body: String, secretKey: String): String {
            val prehash = timestamp + method.uppercase() + requestPath + body
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secretKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            return Base64.getEncoder().encodeToString(mac.doFinal(prehash.toByteArray(StandardCharsets.UTF_8)))
        }
    }
}

/** Stores each credential independently using Android Keystore-backed AES-GCM. */
class OkxCredentialStore(private val vault: ApiSecretVault) {
    fun save(credentials: OkxCredentials) {
        vault.save(API_KEY, credentials.apiKey)
        vault.save(SECRET_KEY, credentials.secretKey)
        vault.save(PASSPHRASE, credentials.passphrase)
    }

    fun load(): OkxCredentials? {
        val apiKey = vault.read(API_KEY) ?: return null
        val secret = vault.read(SECRET_KEY) ?: return null
        val passphrase = vault.read(PASSPHRASE) ?: return null
        return OkxCredentials(apiKey, secret, passphrase)
    }

    fun clear() {
        vault.delete(API_KEY)
        vault.delete(SECRET_KEY)
        vault.delete(PASSPHRASE)
    }

    fun isConfigured(): Boolean =
        vault.read(API_KEY) != null && vault.read(SECRET_KEY) != null && vault.read(PASSPHRASE) != null

    private companion object {
        const val API_KEY = "okx_api_key"
        const val SECRET_KEY = "okx_secret_key"
        const val PASSPHRASE = "okx_passphrase"
    }
}
