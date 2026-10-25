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

    /**
     * 当前选中的 agent。存在这里而不是路由参数里，是为了让冷启动能**立刻**用
     * `Room.observeByAgent` 出会话列表，而不必先等 `GET /api/agents` 返回
     * （docs/spec.md §12「冷启动」）。
     */
    val selectedAgentId: Flow<String?> = store.data.map { it[KEY_AGENT_ID] }
    val selectedAgentName: Flow<String?> = store.data.map { it[KEY_AGENT_NAME] }

    suspend fun currentHost(): String? = host.first()

    suspend fun setHost(value: String?) {
        store.edit { prefs ->
            if (value.isNullOrBlank()) {
                prefs.remove(KEY_HOST)
                // 换实例等于换 agent 空间，选中项必须一起清掉，否则会拿着 A 实例的
                // agent_id 去 B 实例拉会话。
                prefs.remove(KEY_AGENT_ID)
                prefs.remove(KEY_AGENT_NAME)
            } else {
                prefs[KEY_HOST] = value
            }
        }
    }

    suspend fun setAllowCleartext(value: Boolean) {
        store.edit { it[KEY_ALLOW_CLEARTEXT] = value }
    }

    suspend fun setSelectedAgent(agentId: String?, name: String? = null) {
        store.edit { prefs ->
            if (agentId.isNullOrBlank()) {
                prefs.remove(KEY_AGENT_ID)
                prefs.remove(KEY_AGENT_NAME)
            } else {
                prefs[KEY_AGENT_ID] = agentId
                if (name.isNullOrBlank()) prefs.remove(KEY_AGENT_NAME) else prefs[KEY_AGENT_NAME] = name
            }
        }
    }

    private companion object {
        val KEY_HOST = stringPreferencesKey("host")
        val KEY_ALLOW_CLEARTEXT = booleanPreferencesKey("allow_cleartext")
        val KEY_AGENT_ID = stringPreferencesKey("selected_agent_id")
        val KEY_AGENT_NAME = stringPreferencesKey("selected_agent_name")
    }
}
