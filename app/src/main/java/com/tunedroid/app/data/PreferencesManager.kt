package com.tunedroid.app.data

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {

    companion object {
        private val STORAGE_PATH = stringPreferencesKey("storage_path")
        private val DEFAULT_FORMAT = stringPreferencesKey("default_format")
        private val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        private val AUTO_UPDATE_ENGINE = booleanPreferencesKey("auto_update_engine")
        private val DELETE_ORIGINAL = booleanPreferencesKey("delete_original")
        private val WIFI_ONLY = booleanPreferencesKey("wifi_only")

        private const val MIGRATION_V4_DONE = "migration_v4_done"

        /**
         * One-time migration for v1.0.4:
         * Resets AUTO_UPDATE_ENGINE to false for users upgrading from v1.0.2
         * where the default was true and the stored value may persist.
         */
        suspend fun runMigrations(context: Context) {
            val sharedPrefs = context.getSharedPreferences("tunedroid_prefs", Context.MODE_PRIVATE)
            if (!sharedPrefs.getBoolean(MIGRATION_V4_DONE, false)) {
                context.dataStore.edit { prefs ->
                    prefs[AUTO_UPDATE_ENGINE] = false
                }
                sharedPrefs.edit().putBoolean(MIGRATION_V4_DONE, true).apply()
            }
        }
    }

    val storagePath: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[STORAGE_PATH] ?: defaultStoragePath()
    }

    val defaultFormat: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEFAULT_FORMAT] ?: "MP3_128"
    }

    val autoCheckUpdates: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_CHECK_UPDATES] ?: true
    }

    val autoUpdateEngine: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_UPDATE_ENGINE] ?: false
    }

    val deleteOriginal: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DELETE_ORIGINAL] ?: true
    }

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[WIFI_ONLY] ?: false
    }

    suspend fun setStoragePath(path: String) {
        context.dataStore.edit { it[STORAGE_PATH] = path }
    }

    suspend fun setDefaultFormat(format: String) {
        context.dataStore.edit { it[DEFAULT_FORMAT] = format }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_CHECK_UPDATES] = enabled }
    }

    suspend fun setAutoUpdateEngine(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_UPDATE_ENGINE] = enabled }
    }

    suspend fun setDeleteOriginal(enabled: Boolean) {
        context.dataStore.edit { it[DELETE_ORIGINAL] = enabled }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[WIFI_ONLY] = enabled }
    }

    private fun defaultStoragePath(): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "TuneDroid"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }
}
