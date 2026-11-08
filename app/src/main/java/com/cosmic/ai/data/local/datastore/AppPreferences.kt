// language: kotlin, file: AppPreferences.kt, runtime: DataStore, target: settings
package com.cosmic.ai.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cosmic_prefs")

class AppPreferences(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO)

    private object Keys {
        val FIRST_LAUNCH = booleanPreferencesKey("first_launch")
        val THEME = stringPreferencesKey("theme")
        val DEFAULT_PROVIDER = stringPreferencesKey("default_provider")
        val PROJECT_URI = stringPreferencesKey("current_project_uri")
        val ALLOW_FILE_READ = booleanPreferencesKey("allow_file_read")
        val ALLOW_FILE_WRITE = booleanPreferencesKey("allow_file_write")
        val ALLOW_TERMINAL = booleanPreferencesKey("allow_terminal")
        val ALLOW_NETWORK = booleanPreferencesKey("allow_network")
        val REQUIRE_APPROVAL = booleanPreferencesKey("require_approval")
        val AUTO_APPROVE = booleanPreferencesKey("auto_approve")
    }

    val isFirstLaunch: Flow<Boolean> = context.dataStore.data.map { it[Keys.FIRST_LAUNCH] ?: true }

    fun setFirstLaunchComplete() {
        scope.launch {
            context.dataStore.edit { it[Keys.FIRST_LAUNCH] = false }
        }
    }

    val theme: Flow<String> = context.dataStore.data.map { it[Keys.THEME] ?: "system" }
    val currentProjectUri: Flow<String?> = context.dataStore.data.map { it[Keys.PROJECT_URI] }

    suspend fun setCurrentProjectUri(value: String?) {
        context.dataStore.edit {
            if (value == null) it.remove(Keys.PROJECT_URI) else it[Keys.PROJECT_URI] = value
        }
    }

    suspend fun setTheme(value: String) {
        context.dataStore.edit { it[Keys.THEME] = value }
    }

    val allowFileRead: Flow<Boolean> = context.dataStore.data.map { it[Keys.ALLOW_FILE_READ] ?: true }
    val allowFileWrite: Flow<Boolean> = context.dataStore.data.map { it[Keys.ALLOW_FILE_WRITE] ?: false }
    val allowTerminal: Flow<Boolean> = context.dataStore.data.map { it[Keys.ALLOW_TERMINAL] ?: false }
    val allowNetwork: Flow<Boolean> = context.dataStore.data.map { it[Keys.ALLOW_NETWORK] ?: true }
    val requireApproval: Flow<Boolean> = context.dataStore.data.map { it[Keys.REQUIRE_APPROVAL] ?: true }
    val autoApprove: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_APPROVE] ?: false }

    suspend fun setPermission(key: String, value: Boolean) {
        context.dataStore.edit {
            when (key) {
                "file_read" -> it[Keys.ALLOW_FILE_READ] = value
                "file_write" -> it[Keys.ALLOW_FILE_WRITE] = value
                "terminal" -> it[Keys.ALLOW_TERMINAL] = value
                "network" -> it[Keys.ALLOW_NETWORK] = value
                "approval" -> it[Keys.REQUIRE_APPROVAL] = value
                "auto_approve" -> it[Keys.AUTO_APPROVE] = value
            }
        }
    }
}
