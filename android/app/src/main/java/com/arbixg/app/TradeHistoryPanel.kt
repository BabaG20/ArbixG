package com.arbixg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private val HistoryPanel = Color(0xFF111A16)
private val HistoryMuted = Color(0xFF91A39A)
private val HistoryGold = Color(0xFFD8B45A)
private val HistoryGreen = Color(0xFF20D68A)

@Composable
fun TradeHistoryPanel() {
    val context = LocalContext.current
    val database = remember(context) { ArbixDatabase.getInstance(context) }
    val records by remember(database) { database.tradeRecordDao().observeRecent() }
        .collectAsState(initial = emptyList())
    var trackedOrders by remember { mutableStateOf<List<TrackedOrderEntity>>(emptyList()) }
    var recoverableIntentCount by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(database) {
        while (true) {
            trackedOrders = runCatching { database.trackedOrderDao().getRecent() }.getOrDefault(emptyList())
            recoverableIntentCount = runCatching { database.orderIntentDao().getRecoverable().size }.getOrDefault(0)
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
            Text("TRADE DASHBOARD", color = HistoryGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Active: " + activeOrders.size, color = if (activeOrders.isEmpty()) HistoryMuted else HistoryGold, fontSize = 10.sp)
                Text("History: " + records.size, color = HistoryMuted, fontSize = 10.sp)
                Text("Recovery: " + recoverableIntentCount, color = if (recoverableIntentCount == 0) HistoryMuted else HistoryGold, fontSize = 10.sp)
            }
            if (records.isEmpty()) {
                Text("No completed or stopped Demo cycles recorded yet.", color = HistoryMuted, fontSize = 10.sp)
            } else {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide trade history" else "View full trade history (" + records.size + ")")
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(if (expanded) 180.dp else 90.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(if (expanded) records.take(100) else records.take(3), key = { it.id }) { record ->
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
            }
            if (activeOrders.isNotEmpty() || recoverableIntentCount > 0) {
                Text(
                    "Open orders and pending intents are retained locally. Reconcile OKX Demo before resuming a cycle.",
                    color = HistoryGold,
                    fontSize = 9.sp
                )
            }
        }
    }
}
