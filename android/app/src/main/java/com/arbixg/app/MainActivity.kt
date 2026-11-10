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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@Composable
private fun ArbixGApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Hub, contentDescription = null, tint = Gold)
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text("ArbixG", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Text("OKX SPOT · TRIANGULAR ARBITRAGE", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("MARKET CONNECTION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("DISCONNECTED", color = Color(0xFFFFC56B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("No live prices available", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Connect to OKX public market data to populate this screen. No sample prices or simulated profits are displayed.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
                Text("TRADING MODES", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModeCard("Demo", "Live market feed · simulated orders", Icons.Default.Security, Modifier.weight(1f))
                    ModeCard("Live", "Locked until safety checks pass", Icons.Default.AccountBalanceWallet, Modifier.weight(1f))
                }
                Text("ENGINE STATUS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusLine("Market feed", "Not connected")
                        StatusLine("Order-book validation", "Waiting for live data")
                        StatusLine("Account fee tier", "Not configured")
                        StatusLine("Automatic execution", "Disabled")
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Build 0.1.0 · Live trading is disabled", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun ModeCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = Gold)
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
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
