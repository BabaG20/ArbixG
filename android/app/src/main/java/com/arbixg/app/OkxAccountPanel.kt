package com.arbixg.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arbixg.app.data.OkxCredentialStore
import com.arbixg.app.data.OkxCredentials
import com.arbixg.app.data.OkxPrivateApi
import com.arbixg.app.data.OkxAccountSnapshot
import com.arbixg.app.data.ReconciledOrder
import com.arbixg.app.data.OrderFillReconciler
import com.arbixg.app.security.ApiSecretVault
import kotlinx.coroutines.launch

@Composable
fun OkxAccountPanel(instrumentIds: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { OkxCredentialStore(ApiSecretVault(context)) }
    var apiKey by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Credentials are not loaded into the screen. Use a read-only OKX API key.") }
    var account by remember { mutableStateOf<OkxAccountSnapshot?>(null) }
    var pendingOrders by remember { mutableStateOf<List<ReconciledOrder>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151F31)), shape = RoundedCornerShape(14.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("OKX ACCOUNT · READ-ONLY DEMO CHECK", color = Color(0xFFE7B95B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Use an API key with Read permission only. Do not enable Trade or Withdraw. Credentials are encrypted with Android Keystore and are never sent to GitHub.", color = Color(0xFF9AA8BC), fontSize = 10.sp)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("OKX API key") }
            )
            OutlinedTextField(
                value = secretKey,
                onValueChange = { secretKey = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Secret key") }
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Passphrase") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && apiKey.isNotBlank() && secretKey.isNotBlank() && passphrase.isNotBlank(),
                    onClick = {
                        try {
                            store.save(OkxCredentials(apiKey.trim(), secretKey.trim(), passphrase))
                            apiKey = ""
                            secretKey = ""
                            passphrase = ""
                            status = "Credentials encrypted on this device. Ready for demo read-only verification."
                            account = null
                        } catch (error: Exception) {
                            status = "Could not save credentials: ${error.message ?: "unknown error"}"
                        }
                    }
                ) { Text("Save securely") }
                OutlinedButton(
                    enabled = !busy && store.isConfigured() && instrumentIds.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Checking OKX demo account, balances and fee tier…"
                            try {
                                val credentials = store.load() ?: error("Save all three credentials first.")
                                val api = OkxPrivateApi(credentials)
                                account = api.loadAccountSnapshot(
                                    instrumentIds = instrumentIds.take(10),
                                    demoMode = true
                                )
                                pendingOrders = api.loadPendingSpotOrders(demoMode = true).map {
                                    OrderFillReconciler.reconcile(null, it)
                                }
                                status = "OKX demo read-only verification succeeded. Existing pending orders were queried; no orders were submitted or changed."
                            } catch (error: Exception) {
                                account = null
                                status = "Account check failed: ${error.message ?: "unknown error"}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "Checking…" else "Test demo account") }
            }
            OutlinedButton(
                enabled = !busy && store.isConfigured(),
                onClick = {
                    try {
                        store.clear()
                        account = null
                        pendingOrders = emptyList()
                        status = "Saved OKX credentials deleted from this device."
                    } catch (error: Exception) {
                        status = "Could not clear credentials: ${error.message ?: "unknown error"}"
                    }
                }
            ) { Text("Delete saved credentials") }
            Text(status, color = Color(0xFF9AA8BC), fontSize = 10.sp)
            account?.let { data ->
                Text("DEMO ACCOUNT VERIFIED · UID ${data.uid ?: "not supplied"} · account level ${data.accountLevel ?: "not supplied"}", color = Color(0xFF79D6A3), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Balances", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (data.balances.isEmpty()) Text("No currency balances returned.", color = Color(0xFF9AA8BC), fontSize = 10.sp)
                data.balances.filter { it.available.toDoubleOrNull()?.let { value -> value > 0.0 } == true || it.frozen.toDoubleOrNull()?.let { value -> value > 0.0 } == true }
                    .take(20).forEach { balance ->
                        Text("${balance.currency}: available ${balance.available} · frozen ${balance.frozen}", color = Color.White, fontSize = 10.sp)
                    }
                Text("Spot fee tier", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                data.fees.forEach { fee ->
                    Text("${fee.instrumentId}: maker ${fee.makerRate} · taker ${fee.takerRate} · fee currency ${fee.feeCurrency ?: "not supplied"} · level ${fee.level ?: "not supplied"}", color = Color(0xFF9AA8BC), fontSize = 10.sp)
                }
                Text("Retrieved ${data.retrievedAtEpochMs}. Demo environment only. Balance and fees must be refreshed before each future simulation.", color = Color(0xFFE7B95B), fontSize = 9.sp)
                Text("Pending Spot orders · read-only", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (pendingOrders.isEmpty()) {
                    Text("No pending Spot orders were returned.", color = Color(0xFF9AA8BC), fontSize = 10.sp)
                } else {
                    pendingOrders.take(20).forEach { order ->
                        Text("${order.instrumentId} · ${order.status} · filled ${order.cumulativeFilledSize}/${order.requestedSize} · remaining ${order.remainingSize}", color = Color(0xFF9AA8BC), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
