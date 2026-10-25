package com.octopmobile.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "octop_prefs")

/** 本地配置：实例地址 + 是否放行明文 HTTP。 */
class AppPrefs(context: Context) {

    private val store = context.applicationContext.dataStore

    val host: Flow<String?> = store.data.map { it[KEY_HOST] }

    val allowCleartext: Flow<Boolean> = store.data.map { it[KEY_ALLOW_CLEARTEXT] ?: false }

    suspend fun currentHost(): String? = host.first()

    suspend fun setHost(value: String?) {
        store.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(KEY_HOST) else prefs[KEY_HOST] = value
        }
    }

    suspend fun setAllowCleartext(value: Boolean) {
        store.edit { it[KEY_ALLOW_CLEARTEXT] = value }
    }

    private companion object {
        val KEY_HOST = stringPreferencesKey("host")
        val KEY_ALLOW_CLEARTEXT = booleanPreferencesKey("allow_cleartext")
    }
}
