package com.arbixg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbixg.app.data.MarketSnapshot
import com.arbixg.app.data.OkxAccountSnapshot
import com.arbixg.app.data.OkxAssetExclusionSnapshot
import com.arbixg.app.data.OpportunityAssetFilter
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

private val Ink = Color(0xFF080D0B)
private val Panel = Color(0xFF111A16)
private val PanelRaised = Color(0xFF17231D)
private val Gold = Color(0xFFD8B45A)
private val Muted = Color(0xFF91A39A)
private val Green = Color(0xFF20D68A)
private val Border = Color(0xFF26382F)

private enum class AppTab(val label: String) {
    HOME("Home"), SCAN("Scanner"), ACCOUNT("Account"), ACTIVITY("Activity"), SETTINGS("Settings")
}

@Composable
private fun ArbixGApp() {
    val repository = remember { OkxMarketRepository() }
    val orderBookRepository = remember { OkxOrderBookRepository() }
    val depthEngine = remember {
        DepthValidationEngine(loadOrderBook = { instrumentId -> orderBookRepository.load(instrumentId) })
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var updateRelease by remember { mutableStateOf<DirectUpdater.Release?>(null) }
    var updateMessage by remember { mutableStateOf("Bonyeza Check for updates kuangalia toleo jipya.") }
    var updateBusy by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<MarketSnapshot?>(null) }
    var accountSnapshot by remember { mutableStateOf<OkxAccountSnapshot?>(null) }
    var assetExclusions by remember { mutableStateOf<OkxAssetExclusionSnapshot?>(null) }
    var connection by remember { mutableStateOf("CONNECTING") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastSuccessAt by remember { mutableStateOf<Long?>(null) }
    var depthValidation by remember { mutableStateOf<List<DepthValidationItem>>(emptyList()) }
    var depthMessage by remember { mutableStateOf("Inasubiri market snapshot ya kwanza.") }

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

    val exclusionSnapshot = assetExclusions?.takeIf {
        it.complete && (System.currentTimeMillis() - it.retrievedAtEpochMs) in 0L..300_000L
    }
    val filterResult = remember(snapshot, exclusionSnapshot) {
        val market = snapshot
        val exclusions = exclusionSnapshot
        if (market != null && exclusions != null) {
            runCatching { OpportunityAssetFilter.apply(market.candidates, market.tickers, exclusions) }.getOrNull()
        } else null
    }
    val visibleCandidates = remember(snapshot, filterResult, depthValidation) {
        val candidates = filterResult?.included ?: snapshot?.candidates.orEmpty()
        val depthRank = depthValidation.mapIndexed { index, item ->
            "${item.candidate.path.joinToString(">")}|${item.candidate.instruments.joinToString(",")}" to index
        }.toMap()
        candidates.sortedBy { candidate ->
            depthRank["${candidate.path.joinToString(">")}|${candidate.instruments.joinToString(",")}"] ?: Int.MAX_VALUE
        }.take(50)
    }

    LaunchedEffect(repository, accountSnapshot?.retrievedAtEpochMs, assetExclusions?.retrievedAtEpochMs) {
        var retryDelayMs = 15_000L
        while (true) {
            var waitMs = 15_000L
            try {
                val fresh = repository.loadAllSpotMarkets()
                snapshot = fresh
                connection = "LIVE SNAPSHOT"
                errorMessage = null
                lastSuccessAt = System.currentTimeMillis()
                depthMessage = "Inakagua order-book depth kwa kila USDT cycle inayokidhi masharti…"
                try {
                    val usableAccount = accountSnapshot?.takeIf {
                        it.feeType in setOf("0", "1") &&
                            (System.currentTimeMillis() - it.retrievedAtEpochMs) in 0L..300_000L
                    }
                    val accountFeeRates = if (usableAccount == null) null else {
                        val parsed = usableAccount.fees.mapNotNull { fee ->
                            fee.takerRate.toDoubleOrNull()?.let { kotlin.math.abs(it) }
                                ?.takeIf { it.isFinite() && it < 1.0 }
                                ?.let { fee.instrumentId to it }
                        }.toMap()
                        parsed.takeIf { it.size == usableAccount.fees.size }
                    }
                    val freshFilter = assetExclusions?.takeIf {
                        it.complete && (System.currentTimeMillis() - it.retrievedAtEpochMs) in 0L..300_000L
                    }?.let { OpportunityAssetFilter.apply(fresh.candidates, fresh.tickers, it) }
                    val candidatesForValidation = freshFilter?.included ?: fresh.candidates
                    depthValidation = depthEngine.validateUsdtCandidates(
                        candidates = candidatesForValidation,
                        startAmountUsdt = 10.0,
                        instrumentRules = fresh.instrumentRules,
                        slippageBufferBps = 10.0,
                        feeRatesByInstrument = accountFeeRates,
                        feeType = if (accountFeeRates != null) usableAccount?.feeType?.toIntOrNull() else null
                    )
                    val feeAdjustedCount = depthValidation.count { it.feeModel != null }
                    depthMessage = when {
                        candidatesForValidation.isEmpty() && freshFilter?.excluded?.isNotEmpty() == true ->
                            "Cycles zote zilizogunduliwa zimeondolewa kutokana na Spot holdings, Earn holdings au open Spot orders."
                        depthValidation.isEmpty() ->
                            "Hakuna USDT cycle inayokidhi masharti ya depth validation kwenye snapshot hii."
                        feeAdjustedCount > 0 ->
                            "Depth imekaguliwa kwa cycles ${depthValidation.size}. ${freshFilter?.excluded?.size ?: 0} zimeondolewa na holdings/Earn/open orders. ${feeAdjustedCount} zina makadirio ya ada za akaunti pamoja na slippage ya 10 bps kwa kila leg."
                        else ->
                            "Depth imekaguliwa kwa cycles ${depthValidation.size} kwa slippage ya 10 bps kwa kila leg. Hakuna snapshot kamili ya ada za akaunti; makadirio ni gross-only."
                    }
                } catch (depthError: Exception) {
                    depthValidation = emptyList()
                    depthMessage = "Depth validation haipatikani: ${depthError.message ?: "unknown error"}"
                }
                retryDelayMs = 15_000L
                waitMs = 15_000L
            } catch (error: Exception) {
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

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Green, secondary = Gold, background = Ink, surface = Panel,
        onPrimary = Ink, onSecondary = Ink, onBackground = Color.White, onSurface = Color.White, outline = Border
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Scaffold(
                containerColor = Ink,
                topBar = { AppHeader(connection) },
                bottomBar = {
                    NavigationBar(containerColor = Panel, contentColor = Muted) {
                        val tabs = listOf(
                            Triple(AppTab.HOME, Icons.Default.Home, "Home"),
                            Triple(AppTab.SCAN, Icons.Default.Search, "Scanner"),
                            Triple(AppTab.ACCOUNT, Icons.Default.AccountBalanceWallet, "Account"),
                            Triple(AppTab.ACTIVITY, Icons.Default.History, "Activity"),
                            Triple(AppTab.SETTINGS, Icons.Default.Settings, "Settings")
                        )
                        tabs.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = { Icon(item.second, contentDescription = item.third) },
                                label = { Text(item.third, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Green,
                                    selectedTextColor = Green,
                                    indicatorColor = Color(0xFF20372B),
                                    unselectedIconColor = Muted,
                                    unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    when (selectedTab) {
                        0 -> HomeScreen(
                            snapshot, filterResult?.included?.size, connection, errorMessage, lastSuccessAt,
                            visibleCandidates, depthValidation, depthMessage,
                            onOpenScanner = { selectedTab = 1 }
                        )
                        1 -> ScannerScreen(
                            snapshot, visibleCandidates, depthValidation, depthMessage,
                            connection, errorMessage, filterResult?.included?.size
                        )
                        2 -> AccountScreen(
                            snapshot,
                            onAccountSnapshot = { accountSnapshot = it },
                            onAssetExclusions = { assetExclusions = it }
                        )
                        3 -> ActivityScreen()
                        else -> SettingsScreen(
                            updateMessage, updateBusy,
                            onCheckUpdates = {
                                scope.launch {
                                    updateBusy = true
                                    updateRelease = null
                                    updateMessage = "Inaangalia toleo jipya…"
                                    try {
                                        when (val result = DirectUpdater.check(context)) {
                                            DirectUpdater.CheckResult.Current ->
                                                updateMessage = "ArbixG iko kwenye toleo la sasa."
                                            is DirectUpdater.CheckResult.Available -> {
                                                updateRelease = result.release
                                                updateMessage = "Toleo ${result.release.versionName} limepatikana. Linapakuliwa na kuthibitishwa…"
                                                updateMessage = DirectUpdater.downloadAndOpenInstaller(context, result.release)
                                            }
                                            is DirectUpdater.CheckResult.Failed ->
                                                updateMessage = "Imeshindikana kuangalia update: ${result.message}"
                                        }
                                    } finally {
                                        updateBusy = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(connection: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Ink).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF193426)) {
            Text("G", modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp), color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text("ArbixG", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("TRIANGULAR INTELLIGENCE", color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = if (connection == "LIVE SNAPSHOT") Green else Gold, modifier = Modifier.padding(end = 5.dp)) {
                    Spacer(Modifier.height(7.dp).padding(horizontal = 4.dp))
                }
                Text(if (connection == "LIVE SNAPSHOT") "LIVE" else "CHECKING", color = if (connection == "LIVE SNAPSHOT") Green else Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Text("OKX SPOT", color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun HomeScreen(
    snapshot: MarketSnapshot?, filteredCount: Int?, connection: String, error: String?, lastSuccessAt: Long?,
    candidates: List<TriangularCandidate>, depth: List<DepthValidationItem>, depthMessage: String, onOpenScanner: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Market overview", "Muhtasari wa soko la OKX Spot kwa wakati halisi")
        Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MARKET SCAN STATUS", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    Text(connection, color = if (connection == "LIVE SNAPSHOT") Green else Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(snapshot?.activeSpotMarkets?.toString() ?: "—", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text("ACTIVE SPOT MARKETS SCANNED", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Cycles", snapshot?.candidates?.size?.toString() ?: "—", Modifier.weight(1f))
                    StatTile("After filters", filteredCount?.toString() ?: "—", Modifier.weight(1f))
                    StatTile("Depth checked", depth.size.toString(), Modifier.weight(1f))
                }
                Text("Full market catalogue · all directed triangular cycles · refresh every 15 seconds", color = Muted, fontSize = 10.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TOP OPPORTUNITIES", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenScanner) { Text("View scanner", color = Green) }
        }
        if (snapshot == null) {
            InfoCard(if (error == null) "Inaunganisha na OKX na kupakua masoko yote…" else "Market data haipatikani: $error")
        } else if (candidates.isEmpty()) {
            InfoCard("Hakuna positive gross triangular candidate kwenye snapshot hii. Hii haimaanishi kuwa fursa haikutokea kati ya refresh.")
        } else {
            candidates.take(3).forEach { candidate ->
                CandidateCard(candidate, depth.firstOrNull { it.candidate.path == candidate.path && it.candidate.instruments == candidate.instruments })
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("DEPTH VALIDATION", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(depthMessage, color = Muted, fontSize = 10.sp)
                Text("Input: 10 USDT · 10 bps slippage buffer · no orders sent", color = Muted, fontSize = 9.sp)
            }
        }
        lastSuccessAt?.let { Text("Last successful refresh: ${DateFormat.getDateTimeInstance().format(Date(it))}", color = Muted, fontSize = 9.sp) }
        Text("Gross candidates are discovery signals, not guaranteed profit. Automatic trading remains disabled.", color = Gold, fontSize = 9.sp)
    }
}

@Composable
private fun ScannerScreen(
    snapshot: MarketSnapshot?, candidates: List<TriangularCandidate>, depth: List<DepthValidationItem>,
    depthMessage: String, connection: String, error: String?, filteredCount: Int?
) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Arbitrage scanner", "Mzunguko wa masoko yote · USDT triangular routes")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Markets", snapshot?.activeSpotMarkets?.toString() ?: "—", Modifier.weight(1f))
            StatTile("Cycles", snapshot?.candidates?.size?.toString() ?: "—", Modifier.weight(1f))
            StatTile("Eligible", filteredCount?.toString() ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(15.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("ORDER-BOOK DEPTH · 10 USDT", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(depthMessage, color = Muted, fontSize = 10.sp)
                Text("Max 4 concurrent requests · 10 bps adverse slippage per leg · no orders sent", color = Muted, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CANDIDATES", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${candidates.size} shown · ${connection}", color = if (connection == "LIVE SNAPSHOT") Green else Gold, fontSize = 9.sp)
        }
        Spacer(Modifier.height(7.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snapshot == null) InfoCard(error ?: "Loading all OKX Spot markets…")
            else if (candidates.isEmpty()) InfoCard("Hakuna positive gross triangular candidates kwenye snapshot hii.")
            else candidates.forEach { candidate ->
                CandidateCard(candidate, depth.firstOrNull { it.candidate.path == candidate.path && it.candidate.instruments == candidate.instruments })
            }
        }
        Text("Gross candidates siyo faida iliyothibitishwa. Data stale haipaswi kutumika kufanya maamuzi ya trade.", color = Gold, fontSize = 9.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun AccountScreen(
    snapshot: MarketSnapshot?, onAccountSnapshot: (OkxAccountSnapshot?) -> Unit,
    onAssetExclusions: (OkxAssetExclusionSnapshot?) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Exchange account", "Muunganisho wa kusoma akaunti ya OKX Demo")
        OkxAccountPanel(
            instrumentIds = snapshot?.candidates.orEmpty()
                .filter { it.path.firstOrNull() == "USDT" && it.path.lastOrNull() == "USDT" }
                .flatMap { it.instruments }.distinct()
                .ifEmpty { snapshot?.instrumentRules?.keys?.take(1).orEmpty() },
            onAccountSnapshot = onAccountSnapshot,
            onAssetExclusions = onAssetExclusions
        )
        InfoCard("Account API ni read-only. API key isiwe na withdrawal permission. Spot/Earn/open-order filter itatumika tu pale snapshot kamili na mpya inapopatikana.")
    }
}

@Composable
private fun ActivityScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Activity & history", "Rekodi zilizohifadhiwa ndani ya kifaa")
        TradeHistoryPanel()
    }
}

@Composable
private fun SettingsScreen(
    updateMessage: String, updateBusy: Boolean, onCheckUpdates: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Settings & safety", "Backup, risk controls na app updates")
        RiskControlPanel()
        BackupPanel()
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("APP UPDATES", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(updateMessage, color = Muted, fontSize = 10.sp)
                Button(
                    enabled = !updateBusy, onClick = onCheckUpdates,
                    colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Ink),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(if (updateBusy) "Please wait…" else "Check for updates", fontWeight = FontWeight.Bold) }
            }
        }
        InfoCard("ArbixG haijawasha automatic trading. Risk controls ni msingi wa usalama; usiweke API key yenye ruhusa ya kutoa fedha.")
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(label.uppercase(), color = Muted, fontSize = 8.sp, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
        Text(message, color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(14.dp))
    }
}

@Composable
private fun CandidateCard(candidate: TriangularCandidate, depth: DepthValidationItem?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(candidate.path.joinToString(" → "), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(String.format("%.4f%%", candidate.grossProfitPct), color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(candidate.instruments.joinToString("  ·  "), color = Muted, fontSize = 9.sp)
            when (depth?.status) {
                DepthValidationStatus.DEPTH_PASSED_GROSS_ONLY, DepthValidationStatus.DEPTH_PASSED_WITH_RULES_GROSS_ONLY -> {
                    Text("DEPTH PASSED · GROSS ONLY", color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("10 USDT → ${String.format("%.8f", depth.finalAmount ?: 0.0)} USDT · gross after slippage ${String.format("%.4f", depth.grossReturnPct ?: 0.0)}%", color = Color.White, fontSize = 10.sp)
                    Text(depth.message + " Not a verified execution result.", color = Gold, fontSize = 9.sp)
                    depth.dustSummary?.let { Text("Residual dust: $it", color = Muted, fontSize = 9.sp) }
                }
                DepthValidationStatus.DEPTH_PASSED_WITH_FEE, DepthValidationStatus.DEPTH_PASSED_WITH_RULES_AND_FEE -> {
                    Text("DEPTH PASSED · FEE-ADJUSTED ESTIMATE", color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("Estimated final ${String.format("%.8f", depth.finalAmount ?: 0.0)} USDT · net after fees + slippage ${String.format("%.4f", depth.netReturnPct ?: 0.0)}%", color = Color.White, fontSize = 10.sp)
                    Text(depth.message + " Fee model: ${depth.feeModel ?: "supplied estimate"}. This is not a verified execution result; balance reservation, minNotional and order fills are not modeled.", color = Gold, fontSize = 9.sp)
                    depth.dustSummary?.let { Text("Residual dust: $it", color = Muted, fontSize = 9.sp) }
                }
                DepthValidationStatus.DEPTH_REJECTED -> {
                    Text("DEPTH REJECTED", color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(depth.message, color = Muted, fontSize = 9.sp)
                }
                null -> Text("Not depth-checked in the current result set.", color = Muted, fontSize = 9.sp)
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
