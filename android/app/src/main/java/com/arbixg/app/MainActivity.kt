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
import com.arbixg.app.data.OkxMarketRepository
import com.arbixg.app.data.SpotTicker
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
    var tickers by remember { mutableStateOf<List<SpotTicker>>(emptyList()) }
    var connection by remember { mutableStateOf("CONNECTING") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastUpdated by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(repository) {
        while (true) {
            try {
                val fresh = repository.loadWatchlist()
                tickers = fresh
                connection = "LIVE"
                errorMessage = null
                lastUpdated = fresh.maxOfOrNull { it.timestamp }
            } catch (error: Exception) {
                connection = "DISCONNECTED"
                errorMessage = error.message ?: "Unable to load OKX market data"
            }
            delay(15_000)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Hub, contentDescription = null, tint = Gold)
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text("ArbixG", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("OKX SPOT · TRIANGULAR ARBITRAGE", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("OKX PUBLIC MARKET FEED", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(connection, color = if (connection == "LIVE") Green else Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            if (tickers.isNotEmpty() && connection == "LIVE") "Live prices received" else "Live prices unavailable",
                            color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            errorMessage ?: "Public OKX ticker endpoint · refresh every 15 seconds",
                            color = Muted, fontSize = 12.sp
                        )
                        lastUpdated?.let {
                            Text("Exchange timestamp: ${DateFormat.getDateTimeInstance().format(Date(it))}", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
                Text("SPOT WATCHLIST", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                if (tickers.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Text("Waiting for verified OKX market data. No sample prices are shown.", color = Muted, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.weight(1f)) {
                        items(tickers, key = { it.instrumentId }) { ticker -> TickerCard(ticker) }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ENGINE SAFETY", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        StatusLine("Market prices", if (connection == "LIVE") "Live REST data" else "Unavailable")
                        StatusLine("Order-book depth", "Not yet connected")
                        StatusLine("Account fee tier", "Not configured")
                        StatusLine("Automatic trading", "Disabled · safety lock")
                        StatusLine("Mode", "Market-data preview only")
                    }
                }
                Text("Live market data is not a trade signal. No orders are sent.", color = Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun TickerCard(ticker: SpotTicker) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ticker.instrumentId, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(ticker.last, color = Green, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Bid  ${ticker.bid}", color = Muted, fontSize = 11.sp)
                Text("Ask  ${ticker.ask}", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
