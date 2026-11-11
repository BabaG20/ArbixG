package com.arbixg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbixg.app.data.ArbixDatabase
import com.arbixg.app.data.TrackedOrderEntity
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val HistoryPanel = Color(0xFF151F31)
private val HistoryMuted = Color(0xFF9AA8BC)
private val HistoryGold = Color(0xFFE7B95B)
private val HistoryGreen = Color(0xFF79D6A3)

@Composable
fun TradeHistoryPanel() {
    val context = LocalContext.current
    val database = remember(context) { ArbixDatabase.getInstance(context) }
    val records by remember(database) { database.tradeRecordDao().observeRecent() }
        .collectAsState(initial = emptyList())
    var trackedOrders by remember { mutableStateOf<List<TrackedOrderEntity>>(emptyList()) }

    LaunchedEffect(database) {
        while (true) {
            trackedOrders = runCatching { database.trackedOrderDao().getRecent() }.getOrDefault(emptyList())
            delay(5_000L)
        }
    }

    val activeOrders = trackedOrders.filter {
        it.status !in setOf("FILLED", "CANCELED", "REJECTED")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = HistoryPanel),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TRADE DASHBOARD", color = HistoryGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Active orders: " + activeOrders.size, color = if (activeOrders.isEmpty()) HistoryMuted else HistoryGold, fontSize = 10.sp)
                Text("History: " + records.size, color = HistoryMuted, fontSize = 10.sp)
            }
            if (records.isEmpty()) {
                Text("No completed or stopped Demo cycles recorded yet.", color = HistoryMuted, fontSize = 10.sp)
            } else {
                records.take(3).forEach { record ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(record.route, color = Color.White, fontSize = 9.sp, maxLines = 1)
                            Text(
                                record.mode + " · " + record.status + " · " +
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.createdAtEpochMs)),
                                color = HistoryMuted,
                                fontSize = 8.sp,
                                maxLines = 1
                            )
                        }
                        Text(
                            record.realizedPnl?.let { String.format("%+.6f", it) } ?: "—",
                            color = record.realizedPnl?.let { if (it >= 0.0) HistoryGreen else HistoryGold } ?: HistoryMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (activeOrders.isNotEmpty()) {
                Text(
                    "Open / partial orders are retained in the local journal. Check OKX Demo before resuming a cycle.",
                    color = HistoryGold,
                    fontSize = 9.sp
                )
            }
        }
    }
}
