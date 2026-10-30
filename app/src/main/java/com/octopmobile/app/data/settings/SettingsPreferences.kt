package com.octopmobile.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "octop_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ThemeColor { BLUE, GREEN, ORANGE, PURPLE, RED }

data class AppSettings(
    val themeMode: ThemeMode,
    val themeColor: ThemeColor,
    val fontScale: Float,
    val voiceReadAloud: Boolean,
    val notificationsEnabled: Boolean,
    val taskResultNotifications: Boolean,
    val confirmationNotifications: Boolean,
    val scheduledTaskNotifications: Boolean,
)

class SettingsPreferences(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            themeMode = prefs[KEY_THEME_MODE].toEnum(ThemeMode.SYSTEM),
            themeColor = prefs[KEY_THEME_COLOR].toEnum(ThemeColor.BLUE),
            fontScale = prefs[KEY_FONT_SCALE] ?: 1f,
            voiceReadAloud = prefs[KEY_VOICE_READ_ALOUD] ?: false,
            notificationsEnabled = prefs[KEY_NOTIFICATIONS] ?: true,
            taskResultNotifications = prefs[KEY_TASK_RESULTS] ?: true,
            confirmationNotifications = prefs[KEY_CONFIRMATIONS] ?: true,
            scheduledTaskNotifications = prefs[KEY_SCHEDULED_TASKS] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(value: ThemeMode) = edit { it[KEY_THEME_MODE] = value.name }
    suspend fun setThemeColor(value: ThemeColor) = edit { it[KEY_THEME_COLOR] = value.name }
    suspend fun setFontScale(value: Float) = edit { it[KEY_FONT_SCALE] = value.coerceIn(0.85f, 1.45f) }
    suspend fun setVoiceReadAloud(value: Boolean) = edit { it[KEY_VOICE_READ_ALOUD] = value }
    suspend fun setNotificationsEnabled(value: Boolean) = edit { it[KEY_NOTIFICATIONS] = value }
    suspend fun setTaskResultNotifications(value: Boolean) = edit { it[KEY_TASK_RESULTS] = value }
    suspend fun setConfirmationNotifications(value: Boolean) = edit { it[KEY_CONFIRMATIONS] = value }
    suspend fun setScheduledTaskNotifications(value: Boolean) = edit { it[KEY_SCHEDULED_TASKS] = value }

    private suspend fun edit(block: (MutablePreferences) -> Unit) = store.edit(block)

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_THEME_COLOR = stringPreferencesKey("theme_color")
        val KEY_FONT_SCALE = floatPreferencesKey("font_scale")
        val KEY_VOICE_READ_ALOUD = booleanPreferencesKey("voice_read_aloud")
        val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val KEY_TASK_RESULTS = booleanPreferencesKey("task_result_notifications")
        val KEY_CONFIRMATIONS = booleanPreferencesKey("confirmation_notifications")
        val KEY_SCHEDULED_TASKS = booleanPreferencesKey("scheduled_task_notifications")
    }
}
