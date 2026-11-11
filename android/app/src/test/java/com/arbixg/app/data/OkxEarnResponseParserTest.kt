package com.arbixg.app.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkxEarnResponseParserTest {
    @Test fun parsesSimpleEarnBalancesOnlyWhenAmountIsPositive() {
        val data = JSONArray("""[
            {"ccy":"USDT","amt":"12.5"},
            {"ccy":"BTC","amt":"0"},
            {"ccy":"ETH","amt":"invalid"}
        ]""")
        val result = OkxEarnResponseParser.parseSimpleEarn(data)
        assertEquals(1, result.size)
        assertEquals("USDT", result.single().currency)
        assertEquals("12.5", result.single().amount)
    }

    @Test fun parsesOnChainInvestmentPrincipalNotRewardCurrency() {
        val data = JSONArray("""[
            {"ccy":"USDT","investData":[{"ccy":"USDT","amt":"50"}],
             "earningData":[{"ccy":"COMP","earnings":"0.01"}]},
            {"ccy":"DOT","investData":[{"ccy":"DOT","amt":"2"}]}
        ]""")
        val result = OkxEarnResponseParser.parseOnChainActive(data)
        assertEquals(setOf("USDT", "DOT"), result.map { it.currency }.toSet())
        assertTrue(result.all { it.product == "On-chain Earn" })
    }

    @Test fun parsesStableRewardsNestedDetails() {
        val data = JSONArray("""[{"details":[
            {"ccy":"USDG","amt":"25","state":"earning"},
            {"ccy":"USDC","amt":"0","state":"pending"}
        ]}]""")
        val result = OkxEarnResponseParser.parseStableRewards(data)
        assertEquals(listOf("USDG"), result.map { it.currency })
    }

    @Test fun parsesOnlyActiveDualInvestmentOrders() {
        val data = JSONArray("""[
            {"state":"live","notionalCcy":"BTC","notionalSz":"0.02"},
            {"state":"pending_settle","notionalCcy":"USDT","notionalSz":"100"},
            {"state":"settled","notionalCcy":"ETH","notionalSz":"3"}
        ]""")
        val result = OkxEarnResponseParser.parseDualInvestment(data)
        assertEquals(setOf("BTC", "USDT"), result.map { it.currency }.toSet())
    }

    @Test fun parsesEthSolAndOkusdBalances() {
        val eth = OkxEarnResponseParser.parseEthStaking(JSONArray("""[{"ccy":"BETH","amt":"0.5"}]"""))
        val sol = OkxEarnResponseParser.parseSolStaking(JSONArray("""[{"ccy":"OKSOL","amt":"3"}]"""))
        val okusd = OkxEarnResponseParser.parseOkusd(JSONArray("""[{"ccy":"OKUSD","amt":"9"}]"""))
        assertEquals(listOf("BETH"), eth.map { it.currency })
        assertEquals(listOf("OKSOL"), sol.map { it.currency })
        assertEquals(listOf("OKUSD"), okusd.map { it.currency })
    }
}
