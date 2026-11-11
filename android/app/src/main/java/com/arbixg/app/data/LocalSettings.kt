package com.arbixg.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.arbixSettings by preferencesDataStore(name = "arbixg_settings")

class LocalSettings(private val context: Context) {
    private val modeKey = stringPreferencesKey("trading_mode")
    private val autoTradingKey = booleanPreferencesKey("auto_trading_enabled")

    val tradingMode: Flow<String> = context.arbixSettings.data.map { it[modeKey] ?: "DEMO" }
    val autoTradingEnabled: Flow<Boolean> = context.arbixSettings.data.map { it[autoTradingKey] ?: false }

    suspend fun setTradingMode(mode: String) {
        require(mode == "DEMO" || mode == "LIVE")
        context.arbixSettings.edit { it[modeKey] = mode }
    }

    suspend fun setAutoTradingEnabled(enabled: Boolean) {
        context.arbixSettings.edit { it[autoTradingKey] = enabled }
    }

    /** Export only non-secret preferences. Automatic trading is intentionally never backed up. */
    suspend fun backupTradingMode(): String = context.arbixSettings.data.first()[modeKey] ?: "DEMO"

    /** Restore a supported preference and always force automatic trading OFF on this device. */
    suspend fun restorePreferences(mode: String) {
        require(mode == "DEMO" || mode == "LIVE")
        context.arbixSettings.edit {
            it[modeKey] = mode
            it[autoTradingKey] = false
        }
    }
}
