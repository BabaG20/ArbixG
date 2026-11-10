package com.arbixg.app.backup

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.arbixg.app.data.ArbixDatabase
import com.arbixg.app.data.LocalSettings
import com.arbixg.app.data.TradeRecord
import org.json.JSONArray
import org.json.JSONObject

data class BackupPreview(
    val version: Int,
    val exportedAtEpochMs: Long,
    val tradingMode: String,
    val tradeCount: Int,
    internal val records: List<TradeRecord>
)

/** Device-independent encrypted backup for non-secret app data only. */
class BackupManager(context: Context, private val settings: LocalSettings) {
    private val database = Room.databaseBuilder(
        context.applicationContext, ArbixDatabase::class.java, "arbixg.db"
    ).build()

    suspend fun createEncryptedBackup(password: CharArray): ByteArray {
        val records = database.tradeRecordDao().getAllForBackup()
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAtEpochMs", System.currentTimeMillis())
            .put("preferences", JSONObject().put("tradingMode", settings.backupTradingMode()))
        val trades = JSONArray()
        records.forEach { record ->
            val item = JSONObject()
                .put("mode", record.mode)
                .put("route", record.route)
                .put("status", record.status)
                .put("createdAtEpochMs", record.createdAtEpochMs)
            record.realizedPnl?.let { item.put("realizedPnl", it) }
            trades.put(item)
        }
        root.put("tradeRecords", trades)
        return BackupCrypto.encrypt(root.toString(), password)
    }

    fun inspectEncryptedBackup(bytes: ByteArray, password: CharArray): BackupPreview {
        val root = JSONObject(BackupCrypto.decrypt(bytes, password))
        val version = root.optInt("schemaVersion", -1)
        require(version == SCHEMA_VERSION) { "Backup version is not supported." }
        val exportedAt = root.getLong("exportedAtEpochMs")
        val mode = root.getJSONObject("preferences").getString("tradingMode")
        require(mode == "DEMO" || mode == "LIVE") { "Backup contains an invalid trading mode." }
        val jsonTrades = root.getJSONArray("tradeRecords")
        require(jsonTrades.length() <= MAX_TRADE_RECORDS) { "Backup contains too many trade records." }
        val records = ArrayList<TradeRecord>(jsonTrades.length())
        for (i in 0 until jsonTrades.length()) {
            val item = jsonTrades.getJSONObject(i)
            val tradeMode = item.getString("mode")
            require(tradeMode == "DEMO" || tradeMode == "LIVE") { "Invalid trade mode in backup." }
            val route = item.getString("route")
            val status = item.getString("status")
            require(route.length <= 512 && status.length <= 64) { "Backup record is invalid." }
            val pnl = if (item.has("realizedPnl") && !item.isNull("realizedPnl")) item.getDouble("realizedPnl") else null
            require(pnl == null || pnl.isFinite()) { "Backup contains invalid PnL." }
            records += TradeRecord(0, tradeMode, route, status, item.getLong("createdAtEpochMs"), pnl)
        }
        return BackupPreview(version, exportedAt, mode, records.size, records)
    }

    /** Replaces local history only after the UI has displayed a preview and obtained confirmation. */
    suspend fun restore(preview: BackupPreview) {
        require(preview.version == SCHEMA_VERSION)
        database.withTransaction {
            database.tradeRecordDao().deleteAll()
            if (preview.records.isNotEmpty()) database.tradeRecordDao().insertAll(preview.records)
        }
        settings.restorePreferences(preview.tradingMode)
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_TRADE_RECORDS = 100_000
    }
}
