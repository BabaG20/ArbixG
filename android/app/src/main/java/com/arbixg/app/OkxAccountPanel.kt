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
import com.arbixg.app.data.ArbixDatabase
import com.arbixg.app.data.OrderJournal
import com.arbixg.app.data.OkxCredentials
import com.arbixg.app.data.OkxPrivateApi
import com.arbixg.app.data.OkxAccountSnapshot
import com.arbixg.app.data.OkxAssetExclusionSnapshot
import com.arbixg.app.data.OkxEarnHolding
import com.arbixg.app.data.ReconciledOrder
import com.arbixg.app.data.OrderFillReconciler
import com.arbixg.app.security.ApiSecretVault
import kotlinx.coroutines.launch

@Composable
fun OkxAccountPanel(
    instrumentIds: List<String>,
    onAccountSnapshot: (OkxAccountSnapshot?) -> Unit = {},
    onAssetExclusions: (OkxAssetExclusionSnapshot?) -> Unit = {}
) {
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
    var useDemo by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111A16)), shape = RoundedCornerShape(14.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("OKX ACCOUNT · READ-ONLY", color = Color(0xFFD8B45A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide account settings" else "Configure OKX account") }
            if (expanded) {
            Text("Use an API key with Read permission only. Do not enable Trade or Withdraw. Credentials are encrypted with Android Keystore and are never sent to GitHub.", color = Color(0xFF91A39A), fontSize = 10.sp)
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
                            status = "Credentials encrypted on this device. Choose Demo or Live read-only to verify account data."
                            account = null
                            onAccountSnapshot(null)
                            onAssetExclusions(null)
                        } catch (error: Exception) {
                            status = "Could not save credentials: ${error.message ?: "unknown error"}"
                        }
                    }
                ) { Text("Save securely") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { useDemo = true; account = null; onAccountSnapshot(null); onAssetExclusions(null) }) {
                        Text(if (useDemo) "✓ Demo" else "Demo")
                    }
                    OutlinedButton(enabled = !busy, onClick = { useDemo = false; account = null; onAccountSnapshot(null); onAssetExclusions(null) }) {
                        Text(if (!useDemo) "✓ Live read-only" else "Live read-only")
                    }
                }
                Text(
                    if (useDemo) "Demo environment: simulated account data only."
                    else "Live read-only: retrieves actual balances, fees, Earn holdings and open orders. Use a Read-only API key.",
                    color = Color(0xFFD8B45A), fontSize = 10.sp
                )
                OutlinedButton(
                    enabled = !busy && store.isConfigured() && instrumentIds.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Checking OKX ${if (useDemo) "Demo" else "Live read-only"} account, balances, Earn and fee tier…"
                            try {
                                val credentials = store.load() ?: error("Save all three credentials first.")
                                val api = OkxPrivateApi(credentials)
                                account = api.loadAccountSnapshot(
                                    instrumentIds = instrumentIds,
                                    demoMode = useDemo
                                ).also(onAccountSnapshot)
                                val earnHoldings = try {
                                    api.loadEarnHoldings(demoMode = useDemo)
                                } catch (_: Exception) {
                                    null
                                }
                                val journal = OrderJournal(ArbixDatabase.getInstance(context).trackedOrderDao())
                                val recovered = LinkedHashMap<String, ReconciledOrder>()
                                var refreshFailures = 0

                                // Recover locally journaled orders after restart. Isolate failures per order.
                                journal.getRecent().filter { previous ->
                                    previous.status == com.arbixg.app.data.ReconciledOrderStatus.OPEN ||
                                        previous.status == com.arbixg.app.data.ReconciledOrderStatus.PARTIALLY_FILLED
                                }.forEach { previous ->
                                    try {
                                        val fresh = api.loadOrderSnapshot(previous.instrumentId, previous.orderId, demoMode = useDemo)
                                        val reconciled = OrderFillReconciler.reconcile(previous, fresh)
                                        journal.save(reconciled)
                                        recovered[reconciled.orderId] = reconciled
                                    } catch (_: Exception) {
                                        refreshFailures++
                                        recovered[previous.orderId] = previous
                                    }
                                }

                                try {
                                    val exchangePendingOrders = api.loadPendingSpotOrders(demoMode = useDemo)
                                    exchangePendingOrders.forEach { incoming ->
                                        val previous = journal.get(incoming.orderId)
                                        val reconciled = OrderFillReconciler.reconcile(previous, incoming)
                                        journal.save(reconciled)
                                        recovered[reconciled.orderId] = reconciled
                                    }
                                    pendingOrders = recovered.values.sortedByDescending { it.lastExchangeUpdateEpochMs }
                                    if (earnHoldings != null) {
                                        val spotHeld = account?.balances.orEmpty()
                                            .filter { it.cash.toBigDecimalOrNull()?.signum() == 1 }
                                            .map { it.currency.uppercase() }.toSet()
                                        val earnHeld = earnHoldings.map { it.currency.uppercase() }.toSet()
                                        onAssetExclusions(
                                            OkxAssetExclusionSnapshot(
                                                spotHeldCurrencies = spotHeld,
                                                earnHeldCurrencies = earnHeld,
                                                openSpotOrderInstrumentIds = exchangePendingOrders.map { it.instrumentId }.toSet(),
                                                retrievedAtEpochMs = System.currentTimeMillis(),
                                                complete = true
                                            )
                                        )
                                    } else {
                                        onAssetExclusions(null)
                                    }
                                    status = if (earnHoldings != null) {
                                        "OKX read-only snapshot loaded: Spot holdings, Earn holdings and open Spot orders are available for filtering. ${pendingOrders.size} tracked orders restored/updated; $refreshFailures refreshes unavailable. No orders were submitted or changed."
                                    } else {
                                        "Spot account loaded, but one or more Earn endpoints were unavailable. Holdings/Earn filtering is NOT marked complete. No orders were submitted or changed."
                                    }
                                } catch (orderError: Exception) {
                                    onAssetExclusions(null)
                                    pendingOrders = recovered.values.sortedByDescending { it.lastExchangeUpdateEpochMs }
                                    status = "Account balance and fees loaded. Order refresh incomplete: ${orderError.message ?: "unknown error"}. Previously saved order records remain on this device."
                                }
                            } catch (error: Exception) {
                                account = null
                                onAccountSnapshot(null)
                                onAssetExclusions(null)
                                pendingOrders = emptyList()
                                status = "Account check failed: ${error.message ?: "unknown error"}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "Checking…" else if (useDemo) "Test Demo account" else "Load Live read-only account") }
            OutlinedButton(
                enabled = !busy && store.isConfigured(),
                onClick = {
                    try {
                        store.clear()
                        account = null
                        onAccountSnapshot(null)
                        onAssetExclusions(null)
                        pendingOrders = emptyList()
                        status = "Saved OKX credentials deleted from this device."
                    } catch (error: Exception) {
                        status = "Could not clear credentials: ${error.message ?: "unknown error"}"
                    }
                }
            ) { Text("Delete saved credentials") }
            Text(status, color = Color(0xFF91A39A), fontSize = 10.sp)
            account?.let { data ->
                Text("${if (data.demoMode) "DEMO ACCOUNT VERIFIED" else "LIVE ACCOUNT · READ-ONLY"} · UID ${data.uid ?: "not supplied"} · account level ${data.accountLevel ?: "not supplied"}", color = Color(0xFF20D68A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("Fee mode: ${when (data.feeType) { "0" -> "fees charged in received currency"; "1" -> "fees charged in Spot quote currency"; else -> "unknown — fee-adjusted simulation disabled" }}", color = Color(0xFFD8B45A), fontSize = 10.sp)
                Text("Balances", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (data.balances.isEmpty()) Text("No currency balances returned.", color = Color(0xFF91A39A), fontSize = 10.sp)
                data.balances.filter { it.available.toDoubleOrNull()?.let { value -> value > 0.0 } == true || it.frozen.toDoubleOrNull()?.let { value -> value > 0.0 } == true }
                    .take(20).forEach { balance ->
                        Text("${balance.currency}: available ${balance.available} · frozen ${balance.frozen}", color = Color.White, fontSize = 10.sp)
                    }
                Text("Spot fee tier", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                data.fees.forEach { fee ->
                    Text("${fee.instrumentId}: group ${fee.groupId} · maker ${fee.makerRate} · taker ${fee.takerRate} · level ${fee.level ?: "not supplied"}", color = Color(0xFF91A39A), fontSize = 10.sp)
                }
                Text("Retrieved ${data.retrievedAtEpochMs}. ${if (data.demoMode) "Demo" else "Live read-only"} data only. Balance and fees must be refreshed before each future simulation.", color = Color(0xFFD8B45A), fontSize = 9.sp)
                Text("Pending Spot orders · read-only", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (pendingOrders.isEmpty()) {
                    Text("No pending Spot orders were returned.", color = Color(0xFF91A39A), fontSize = 10.sp)
                } else {
                    pendingOrders.take(20).forEach { order ->
                        Text("${order.instrumentId} · ${order.status} · filled ${order.cumulativeFilledSize}/${order.requestedSize} · remaining ${order.remainingSize}", color = Color(0xFF91A39A), fontSize = 10.sp)
                    }
                }
            }
            }
        }
    }
}
