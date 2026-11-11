package com.arbixg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbixg.app.data.ArbixDatabase
import com.arbixg.app.data.RiskStateEntity
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.launch

@Composable
fun RiskControlPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember(context) { ArbixDatabase.getInstance(context).riskStateDao() }
    var state by remember { mutableStateOf<RiskStateEntity?>(null) }
    var activeCycles by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("Inapakia hali ya risk iliyohifadhiwa…") }

    suspend fun refresh() {
        state = dao.getState()
        activeCycles = dao.getActiveCycleIds()
        loading = false
    }

    LaunchedEffect(dao) {
        try {
            refresh()
            message = "Risk state imesomwa kutoka kwenye database ya kifaa."
        } catch (error: Exception) {
            loading = false
            message = "Imeshindwa kusoma risk state: ${error.message ?: "unknown error"}"
        }
    }

    val enabled = state?.killSwitchEnabled ?: false
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111A16)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("PERSISTENT RISK CONTROL", color = Color(0xFFD8B45A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row {
                Text("Kill switch", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (enabled) "ON · NEW CYCLES BLOCKED" else "OFF",
                    color = if (enabled) Color(0xFFFFB36B) else Color(0xFF20D68A),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "Daily realized loss: ${state?.dailyRealizedLoss ?: "not recorded"} · UTC date: ${state?.utcDate ?: "—"}",
                color = Color(0xFF91A39A),
                fontSize = 10.sp
            )
            Text(
                "Persisted reservations: ${activeCycles.size} active cycle(s)" +
                    if (activeCycles.isEmpty()) "" else " · ${activeCycles.take(4).joinToString()}",
                color = Color(0xFF91A39A),
                fontSize = 10.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            try {
                                val now = System.currentTimeMillis()
                                val utcDate = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString()
                                dao.setKillSwitchEnabled(!enabled, utcDate, now)
                                refresh()
                                message = if (!enabled) {
                                    "Kill switch imewashwa na kuhifadhiwa kwenye kifaa."
                                } else {
                                    "Kill switch imezimwa. Mfumo wa execution bado lazima upite risk checks."
                                }
                            } catch (error: Exception) {
                                loading = false
                                message = "Imeshindwa kubadilisha kill switch: ${error.message ?: "unknown error"}"
                            }
                        }
                    }
                ) { Text(if (enabled) "Disable kill switch" else "Enable kill switch") }
                OutlinedButton(
                    enabled = !loading,
                    onClick = {
                        scope.launch {
                            loading = true
                            try {
                                refresh()
                                message = "Risk state ime-refresh kutoka Room."
                            } catch (error: Exception) {
                                loading = false
                                message = "Refresh failed: ${error.message ?: "unknown error"}"
                            }
                        }
                    }
                ) { Text("Refresh") }
            }
            Text(message, color = Color(0xFF91A39A), fontSize = 10.sp)
            Text(
                "Hii inahifadhi kill switch na reservations. Hakuna order submission iliyounganishwa; hakuna oda inayotumwa.",
                color = Color(0xFFD8B45A),
                fontSize = 9.sp
            )
        }
    }
}
