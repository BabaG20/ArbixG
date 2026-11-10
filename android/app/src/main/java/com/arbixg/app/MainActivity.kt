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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbixg.app.data.MarketSnapshot
import com.arbixg.app.data.OkxMarketRepository
import com.arbixg.app.data.TriangularCandidate
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
    var snapshot by remember { mutableStateOf<MarketSnapshot?>(null) }
    var connection by remember { mutableStateOf("CONNECTING") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastSuccessAt by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(repository) {
        while (true) {
            try {
                val fresh = repository.loadAllSpotMarkets()
                snapshot = fresh
                connection = "LIVE SNAPSHOT"
                errorMessage = null
                lastSuccessAt = System.currentTimeMillis()
            } catch (error: Exception) {
                connection = "DISCONNECTED"
                errorMessage = error.message ?: "Unable to load OKX markets"
            }
            delay(15_000)
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
                        snapshot?.let { Text("Fresh tickers: ${it.tickers.size} · stale rejected: ${it.staleTickerCount} · trading rules retained: ${it.instrumentRules.size}", color = Muted, fontSize = 10.sp) }
                        lastSuccessAt?.let {
                            Text("Last successful refresh: ${DateFormat.getDateTimeInstance().format(Date(it))}", color = Muted, fontSize = 9.sp)
                        }
                    }
                }
                Text("TOP TRIANGULAR CANDIDATES", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                val candidates = snapshot?.candidates.orEmpty()
                if (snapshot == null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                        Text(errorMessage ?: "Loading all OKX Spot markets…", color = Muted, modifier = Modifier.padding(16.dp), fontSize = 12.sp)
                    }
                } else if (candidates.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                        Text("No positive gross triangular candidates in this snapshot. This is not proof that no opportunity exists between refreshes.", color = Muted, modifier = Modifier.padding(16.dp), fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(candidates, key = { it.path.joinToString(">") }) { candidate ->
                            CandidateCard(candidate)
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
                        StatusLine("Order-book depth / fees", "Not validated")
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
private fun CandidateCard(candidate: TriangularCandidate) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(candidate.path.joinToString(" → "), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text(String.format("%.4f%% gross", candidate.grossProfitPct), color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(candidate.instruments.joinToString("  ·  "), color = Muted, fontSize = 10.sp)
            Text("Needs order-book, fee and balance validation", color = Gold, fontSize = 9.sp)
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
