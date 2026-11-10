package com.arbixg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.arbixg.app.data.MarketSnapshot
import com.arbixg.app.data.OkxAccountSnapshot
import com.arbixg.app.data.OkxMarketRepository
import com.arbixg.app.data.OkxOrderBookRepository
import com.arbixg.app.data.DepthValidationEngine
import com.arbixg.app.data.DepthValidationItem
import com.arbixg.app.data.DepthValidationStatus
import com.arbixg.app.data.TriangularCandidate
import com.arbixg.app.update.DirectUpdater
import com.arbixg.app.backup.BackupPanel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ArbixGApp() }
    }
}

private val Ink = Color(0xFF0B1220)
private val Panel = Color(0xFF151F31)
private val Gold = Color(0xFFE7B95B)
private val Muted = Color(0xFF9AA8BC)
private val Green = Color(0xFF79D6A3)

@Composable
private fun ArbixGApp() {
    val repository = remember { OkxMarketRepository() }
    val orderBookRepository = remember { OkxOrderBookRepository() }
    val depthEngine = remember {
        DepthValidationEngine(loadOrderBook = { instrumentId -> orderBookRepository.load(instrumentId) })
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateRelease by remember { mutableStateOf<DirectUpdater.Release?>(null) }
    var updateMessage by remember { mutableStateOf("Checking for app updates…") }
    var snapshot by remember { mutableStateOf<MarketSnapshot?>(null) }
    var accountSnapshot by remember { mutableStateOf<OkxAccountSnapshot?>(null) }
    var connection by remember { mutableStateOf("CONNECTING") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastSuccessAt by remember { mutableStateOf<Long?>(null) }
    var depthValidation by remember { mutableStateOf<List<DepthValidationItem>>(emptyList()) }
    var depthMessage by remember { mutableStateOf("Waiting for the first live market snapshot.") }

    LaunchedEffect(context) {
        when (val result = DirectUpdater.check(context)) {
            DirectUpdater.CheckResult.Current -> updateMessage = "App iko kwenye toleo la sasa."
            is DirectUpdater.CheckResult.Available -> {
                updateRelease = result.release
                updateMessage = "Toleo jipya ${result.release.versionName} linapatikana."
            }
            is DirectUpdater.CheckResult.Failed -> updateMessage = "Update: ${result.message}"
        }
    }

    LaunchedEffect(repository, accountSnapshot?.retrievedAtEpochMs) {
        var retryDelayMs = 15_000L
        while (true) {
            var waitMs = 15_000L
            try {
                val fresh = repository.loadAllSpotMarkets()
                snapshot = fresh
                connection = "LIVE SNAPSHOT"
                errorMessage = null
                lastSuccessAt = System.currentTimeMillis()
                depthMessage = "Checking fresh order-book depth for the top USDT cycles…"
                try {
                    val usableAccount = accountSnapshot?.takeIf {
                        it.demoMode && it.feeType in setOf("0", "1") &&
                            (System.currentTimeMillis() - it.retrievedAtEpochMs) in 0L..300_000L
                    }
                    val accountFeeRates = if (usableAccount == null) null else {
                        val parsed = usableAccount.fees.mapNotNull { fee ->
                            fee.takerRate.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it < 1.0 }
                                ?.let { fee.instrumentId to it }
                        }.toMap()
                        parsed.takeIf { it.size == usableAccount.fees.size }
                    }
                    depthValidation = depthEngine.validateUsdtCandidates(
                        candidates = fresh.candidates,
                        startAmountUsdt = 10.0,
                        instrumentRules = fresh.instrumentRules,
                        feeRatesByInstrument = accountFeeRates,
                        feeType = if (accountFeeRates != null) usableAccount?.feeType?.toIntOrNull() else null
                    )
                    val feeAdjustedCount = depthValidation.count { it.feeModel != null }
                    depthMessage = if (depthValidation.isEmpty()) {
                        "No USDT-starting candidate selected for depth validation."
                    } else if (feeAdjustedCount > 0) {
                        "Depth checked ${depthValidation.size} USDT cycle(s). ${feeAdjustedCount} have per-instrument Demo taker-fee estimates using the account's fee currency mode; remaining cycles are gross-only. Not executable profit."
                    } else {
                        "Depth checked ${depthValidation.size} USDT cycle(s) using public L2 books and live lot/minimum/tick rules. No fresh complete Demo fee snapshot is available, so results remain gross-only."
                    }
                } catch (depthError: Exception) {
                    depthValidation = emptyList()
                    depthMessage = "Depth validation unavailable: ${depthError.message ?: "unknown error"}"
                }
                retryDelayMs = 15_000L
                waitMs = 15_000L
            } catch (error: Exception) {
                // Keep the last rendered snapshot to avoid screen flicker, but mark it stale.
                // Execution remains locked; a stale snapshot is never actionable.
                connection = "STALE / DISCONNECTED"
                val causeText = generateSequence(error as Throwable?) { it.cause }
                    .mapNotNull { it.message }
                    .firstOrNull { it.contains("Unable to resolve host", ignoreCase = true) }
                errorMessage = causeText ?: (error.message ?: "Unable to load OKX markets")
                waitMs = retryDelayMs
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(120_000L)
            }
            delay(waitMs)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Hub, contentDescription = null, tint = Gold)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("ArbixG", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("ALL OKX SPOT MARKETS · TRIANGULAR SCAN", color = Muted, fontSize = 9.sp, letterSpacing = 0.7.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("APP UPDATES", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(updateMessage, color = Muted, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.OutlinedButton(onClick = {
                                scope.launch {
                                    updateMessage = "Inaangalia toleo jipya…"
                                    when (val result = DirectUpdater.check(context)) {
                                        DirectUpdater.CheckResult.Current -> { updateRelease = null; updateMessage = "App iko kwenye toleo la sasa." }
                                        is DirectUpdater.CheckResult.Available -> { updateRelease = result.release; updateMessage = "Toleo ${result.release.versionName} linapatikana." }
                                        is DirectUpdater.CheckResult.Failed -> { updateRelease = null; updateMessage = result.message }
                                    }
                                }
                            }) { Text("Check updates") }
                            if (updateRelease != null) {
                                Button(onClick = {
                                    val release = updateRelease!!
                                    scope.launch { updateMessage = DirectUpdater.downloadAndOpenInstaller(context, release) }
                                }) { Text("Download & install") }
                            }
                        }
                    }
                }
                BackupPanel()
                OkxAccountPanel(
                    instrumentIds = snapshot?.candidates.orEmpty().take(10).flatMap { it.instruments }.distinct(),
                    onAccountSnapshot = { accountSnapshot = it }
                )
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("MARKET-WIDE SCAN", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(connection, color = if (snapshot != null && errorMessage == null) Green else Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            Metric("LIVE PAIRS", snapshot?.activeSpotMarkets?.toString() ?: "—")
                            Metric("CANDIDATES", snapshot?.candidates?.size?.toString() ?: "—")
                            Metric("SCAN", "ALL")
                        }
                        Text(errorMessage ?: "Full live SPOT catalogue + ticker snapshot · refresh every 15 seconds", color = Muted, fontSize = 11.sp)
                        if (connection.startsWith("STALE")) {
                            Text("STALE DATA — displayed for reference only. Do not treat candidates as live opportunities.", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        snapshot?.let { Text("Tickers: ${it.tickers.size} · stale rejected: ${it.staleTickerCount} · instrument rules: ${it.instrumentRules.size}", color = Muted, fontSize = 10.sp) }
                        lastSuccessAt?.let {
                            Text("Last successful refresh: ${DateFormat.getDateTimeInstance().format(Date(it))}", color = Muted, fontSize = 9.sp)
                        }
                    }
                }
                Text("ORDER-BOOK DEPTH VALIDATION · 10 USDT", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(depthMessage, color = Muted, fontSize = 11.sp)
                        Text("Public order books only · max 10 USDT cycles · max 4 concurrent requests · no orders sent", color = Muted, fontSize = 9.sp)
                    }
                }
                Text("TOP TRIANGULAR CANDIDATES", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                val candidates = snapshot?.candidates.orEmpty()
                if (snapshot == null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (errorMessage == null) "Loading all OKX Spot markets…" else "No current market data — candidates are hidden until a fresh OKX response arrives.", color = Muted, fontSize = 12.sp)
                            errorMessage?.let { Text(it, color = Gold, fontSize = 11.sp) }
                            lastSuccessAt?.let { Text("Last successful snapshot: ${DateFormat.getDateTimeInstance().format(Date(it))}", color = Muted, fontSize = 10.sp) }
                        }
                    }
                } else if (candidates.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                        Text("No positive gross triangular candidates in this snapshot. This is not proof that no opportunity exists between refreshes.", color = Muted, modifier = Modifier.padding(16.dp), fontSize = 12.sp)
                    }
                } else {
                    if (connection.startsWith("STALE")) {
                        Text("Last known candidates · not current · trading disabled", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(candidates, key = { it.path.joinToString(">") }) { candidate ->
                            CandidateCard(candidate, depthValidation.firstOrNull { it.candidate.path == candidate.path && it.candidate.instruments == candidate.instruments })
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("EXECUTION GATES", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        StatusLine("Market discovery", "All live Spot instruments")
                        StatusLine("Ticker freshness", "Rejects data older than 60 seconds")
                        StatusLine("Instrument rules", "Lot / min size / tick size retained")
                        StatusLine("Candidate status", "Gross only · unverified")
                        StatusLine("Order-book depth", if (depthValidation.isNotEmpty()) "Checked for selected USDT cycles" else "Not yet checked")
                        StatusLine("Fee model", if (depthValidation.any { it.feeModel != null }) "Demo taker fee estimate · not executable" else "No current complete account fee snapshot")
                        StatusLine("Demo / live orders", "Not connected · locked")
                        StatusLine("Automatic trading", "Disabled")
                    }
                }
                Text("Gross candidates are not confirmed profit. No orders are sent.", color = Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text(label, color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun CandidateCard(candidate: TriangularCandidate, depth: DepthValidationItem?) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(candidate.path.joinToString(" → "), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text(String.format("%.4f%% gross", candidate.grossProfitPct), color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(candidate.instruments.joinToString("  ·  "), color = Muted, fontSize = 10.sp)
            when (depth?.status) {
                DepthValidationStatus.DEPTH_PASSED_GROSS_ONLY, DepthValidationStatus.DEPTH_PASSED_WITH_RULES_GROSS_ONLY -> {
                    Text("DEPTH PASSED · GROSS ONLY", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("10 USDT → ${String.format("%.8f", depth.finalAmount ?: 0.0)} USDT · gross ${String.format("%.4f", depth.grossReturnPct ?: 0.0)}%", color = Color.White, fontSize = 10.sp)
                    Text(depth.message + " Not executable profit.", color = Gold, fontSize = 9.sp)
                    depth.dustSummary?.let { Text("Residual dust: $it", color = Muted, fontSize = 9.sp) }
                }
                DepthValidationStatus.DEPTH_PASSED_WITH_FEE, DepthValidationStatus.DEPTH_PASSED_WITH_RULES_AND_FEE -> {
                    Text("DEPTH PASSED · FEE-ADJUSTED ESTIMATE", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Estimated final ${String.format("%.8f", depth.finalAmount ?: 0.0)} USDT · estimated return ${String.format("%.4f", depth.netReturnPct ?: 0.0)}%", color = Color.White, fontSize = 10.sp)
                    Text(depth.message + " Fee model: ${depth.feeModel ?: "supplied estimate"}. This is not a verified execution result; balance reservation, minNotional and order fills are not modeled.", color = Gold, fontSize = 9.sp)
                    depth.dustSummary?.let { Text("Residual dust: $it", color = Muted, fontSize = 9.sp) }
                }
                DepthValidationStatus.DEPTH_REJECTED -> {
                    Text("DEPTH REJECTED", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(depth.message, color = Muted, fontSize = 9.sp)
                }
                null -> Text("Not depth-checked: outside the selected top USDT cycles.", color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}
