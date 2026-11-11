package com.arbixg.app.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.arbixg.app.data.LocalSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val BackupGold = Color(0xFFD8B45A)
private val BackupMuted = Color(0xFF91A39A)
private val BackupPanel = Color(0xFF111A16)

@Composable
fun BackupPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { BackupManager(context, LocalSettings(context)) }
    var password by remember { mutableStateOf("") }
    var dialogMode by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Backup haijajaribiwa kwenye kifaa hiki bado.") }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var busy by remember { mutableStateOf(false) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = exportBytes
        if (uri != null && bytes != null) {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: error("Imeshindikana kufungua faili la kuhifadhi.")
                    }
                    status = "Backup imehifadhiwa. Ihifadhi sehemu salama na kumbuka password yake."
                } catch (error: Exception) {
                    status = "Backup haijahifadhiwa: ${error.message ?: "hitilafu"}"
                } finally {
                    exportBytes = null
                    busy = false
                }
            }
        } else if (uri == null) {
            exportBytes = null
            status = "Umeghairi kuhifadhi backup."
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    importBytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_BACKUP_BYTES) { "Backup file imezidi 50 MB." }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: error("Imeshindikana kusoma faili.")
                    }
                    dialogMode = "IMPORT_PASSWORD"
                    status = "Faili limesomwa. Ingiza password ili kulikagua."
                } catch (error: Exception) {
                    importBytes = null
                    status = "Imeshindikana kusoma backup: ${error.message ?: "faili batili"}"
                } finally {
                    busy = false
                }
            }
        } else {
            status = "Umeghairi kuchagua backup."
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = BackupPanel)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("BACKUP & RESTORE", color = BackupGold, fontSize = 11.sp)
            Text("Hifadhi trade history na trading mode kwa faili lililosimbwa. API keys hazijumuishwi; automatic trading itazimwa baada ya restore.", color = BackupMuted, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { password = ""; dialogMode = "EXPORT_PASSWORD" }) {
                    Text("Create backup")
                }
                OutlinedButton(enabled = !busy, onClick = { openDocument.launch(arrayOf("*/*")) }) {
                    Text("Restore backup")
                }
            }
            Text(status, color = BackupMuted, fontSize = 10.sp)
        }
    }

    if (dialogMode == "EXPORT_PASSWORD" || dialogMode == "IMPORT_PASSWORD") {
        val exporting = dialogMode == "EXPORT_PASSWORD"
        AlertDialog(
            onDismissRequest = { dialogMode = null; password = "" },
            title = { Text(if (exporting) "Weka backup password" else "Fungua backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (exporting) "Tumia password ya angalau herufi 12. Ukiipoteza, backup haiwezi kufunguliwa." else "Ingiza password uliyotumia kutengeneza backup.")
                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("Backup password") }
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = password.length >= 12 && !busy, onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    dialogMode = null
                    scope.launch {
                        busy = true
                        try {
                            if (exporting) {
                                exportBytes = withContext(Dispatchers.IO) {
                                    manager.createEncryptedBackup(chars)
                                }
                                createDocument.launch("ArbixG-backup.arbixg")
                                status = "Chagua mahali pa kuhifadhi faili la backup."
                            } else {
                                val bytes = importBytes ?: error("Hakuna faili lililochaguliwa.")
                                preview = withContext(Dispatchers.IO) {
                                    manager.inspectEncryptedBackup(bytes, chars)
                                }
                                dialogMode = "CONFIRM_RESTORE"
                            }
                        } catch (error: Exception) {
                            status = "Backup haijakamilika: ${error.message ?: "hitilafu"}"
                            importBytes = null
                            exportBytes = null
                        } finally {
                            chars.fill('\u0000')
                            busy = false
                        }
                    }
                }) { Text(if (exporting) "Encrypt" else "Verify") }
            },
            dismissButton = { TextButton(onClick = { dialogMode = null; password = "" }) { Text("Cancel") } }
        )
    }

    if (dialogMode == "CONFIRM_RESTORE" && preview != null) {
        val checked = preview!!
        AlertDialog(
            onDismissRequest = { dialogMode = null; preview = null; importBytes = null },
            title = { Text("Thibitisha kurejesha backup") },
            text = {
                Text("Faili lina trade records ${checked.tradeCount}, trading mode ${checked.tradingMode}. Historia iliyopo kwenye simu hii itafutwa na kubadilishwa na historia ya backup. Automatic trading itazimwa. Endelea?")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    dialogMode = null
                    scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) { manager.restore(checked) }
                            status = "Restore imekamilika kwa mafanikio. Automatic trading imezimwa."
                        } catch (error: Exception) {
                            status = "Restore imeshindwa: ${error.message ?: "hitilafu"}"
                        } finally {
                            preview = null
                            importBytes = null
                            busy = false
                        }
                    }
                }) { Text("Restore now") }
            },
            dismissButton = {
                TextButton(onClick = { dialogMode = null; preview = null; importBytes = null }) { Text("Cancel") }
            }
        )
    }
}

private const val MAX_BACKUP_BYTES = 50 * 1024 * 1024
