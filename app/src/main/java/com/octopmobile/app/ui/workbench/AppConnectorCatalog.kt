package com.octopmobile.app.ui.workbench

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.octopmobile.app.R
import com.octopmobile.app.data.db.MobileSourceType

enum class ConnectorPlatform(@StringRes val labelRes: Int, @StringRes val sectionRes: Int) {
    ANDROID(R.string.connector_tab_current_android, R.string.connector_section_android_available),
    IOS(R.string.connector_tab_ios, R.string.connector_section_ios),
    MAC(R.string.connector_tab_mac, R.string.connector_section_mac),
    WINDOWS(R.string.connector_tab_windows, R.string.connector_section_windows),
}

data class AppConnectorDefinition(
    val id: String,
    val platform: ConnectorPlatform,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val sourceType: String? = null,
    val available: Boolean = true,
)

private val androidConnectors = listOf(
    AppConnectorDefinition(
        id = "alarm_timer",
        platform = ConnectorPlatform.ANDROID,
        titleRes = R.string.connector_alarm_timer_title,
        descriptionRes = R.string.connector_alarm_timer_description,
        icon = Icons.Filled.Alarm,
        tint = Color(0xFFD9534F),
        sourceType = MobileSourceType.ANDROID_ALARM_TIMER,
    ),
    AppConnectorDefinition(
        id = "usage_stats",
        platform = ConnectorPlatform.ANDROID,
        titleRes = R.string.connector_usage_title,
        descriptionRes = R.string.connector_usage_description,
        icon = Icons.Filled.BarChart,
        tint = Color(0xFF7C5AC7),
        sourceType = MobileSourceType.ANDROID_USAGE_STATS,
    ),
    AppConnectorDefinition(
        id = "calendar",
        platform = ConnectorPlatform.ANDROID,
        titleRes = R.string.connector_calendar_title,
        descriptionRes = R.string.connector_calendar_description,
        icon = Icons.Filled.CalendarMonth,
        tint = Color(0xFFD9534F),
        sourceType = MobileSourceType.SYSTEM_CALENDAR,
    ),
    AppConnectorDefinition(
        id = "contacts",
        platform = ConnectorPlatform.ANDROID,
        titleRes = R.string.connector_contacts_title,
        descriptionRes = R.string.connector_contacts_description,
        icon = Icons.Filled.Contacts,
        tint = Color(0xFF36A5A8),
        sourceType = MobileSourceType.ANDROID_CONTACTS,
    ),
    AppConnectorDefinition(
        id = "files",
        platform = ConnectorPlatform.ANDROID,
        titleRes = R.string.connector_files_title,
        descriptionRes = R.string.connector_files_description,
        icon = Icons.Filled.Folder,
        tint = Color(0xFFB07B2B),
        sourceType = MobileSourceType.ANDROID_FILES,
    ),
    AppConnectorDefinition(
        id = "location",
        platform = ConnectorPlatform.ANDROID,
        titleRes = R.string.connector_location_title,
        descriptionRes = R.string.connector_location_description,
        icon = Icons.Filled.LocationOn,
        tint = Color(0xFF4E86C7),
        sourceType = MobileSourceType.ANDROID_LOCATION,
    ),
    AppConnectorDefinition(
        id = "notifications",
        platform = ConnectorPlatform.ANDROID,
        titleRes = R.string.connector_notifications_title,
        descriptionRes = R.string.connector_notifications_description,
        icon = Icons.Filled.Notifications,
        tint = Color(0xFF7560B8),
        sourceType = MobileSourceType.ANDROID_NOTIFICATIONS,
    ),
    AppConnectorDefinition(
        id = "sms",
        platform = ConnectorPlatform.ANDROID,
        titleRes = R.string.connector_sms_title,
        descriptionRes = R.string.connector_sms_description,
        icon = Icons.Filled.Sms,
        tint = Color(0xFF42A36B),
        sourceType = MobileSourceType.ANDROID_SMS,
    ),
)

private val iosConnectors = listOf(
    planned("ios_calendar", ConnectorPlatform.IOS, R.string.connector_apple_calendar_title, R.string.connector_calendar_description, Icons.Filled.CalendarMonth, Color(0xFFD9534F)),
    planned("ios_contacts", ConnectorPlatform.IOS, R.string.connector_apple_contacts_title, R.string.connector_contacts_description, Icons.Filled.Contacts, Color(0xFF36A5A8)),
    planned("ios_health", ConnectorPlatform.IOS, R.string.connector_apple_health_title, R.string.connector_apple_health_description, Icons.Filled.Favorite, Color(0xFFE45876)),
    planned("ios_music", ConnectorPlatform.IOS, R.string.connector_apple_music_title, R.string.connector_apple_music_description, Icons.Filled.MusicNote, Color(0xFFE33C61)),
    planned("ios_photos", ConnectorPlatform.IOS, R.string.connector_photos_title, R.string.connector_photos_description, Icons.Filled.PhotoLibrary, Color(0xFFF3A62D)),
    planned("ios_reminders", ConnectorPlatform.IOS, R.string.connector_apple_reminders_title, R.string.connector_reminders_description, Icons.Filled.NotificationsActive, Color(0xFF4E86C7)),
    planned("ios_location", ConnectorPlatform.IOS, R.string.connector_iphone_location_title, R.string.connector_location_description, Icons.Filled.PhoneIphone, Color(0xFF4E86C7)),
)

private val macConnectors = listOf(
    planned("mac_calendar", ConnectorPlatform.MAC, R.string.connector_apple_calendar_title, R.string.connector_calendar_description, Icons.Filled.CalendarMonth, Color(0xFFD9534F)),
    planned("mac_contacts", ConnectorPlatform.MAC, R.string.connector_apple_contacts_title, R.string.connector_contacts_description, Icons.Filled.Contacts, Color(0xFF36A5A8)),
    planned("mac_mail", ConnectorPlatform.MAC, R.string.connector_apple_mail_title, R.string.connector_apple_mail_description, Icons.Filled.Mail, Color(0xFF4E86C7)),
    planned("mac_notes", ConnectorPlatform.MAC, R.string.connector_apple_notes_title, R.string.connector_apple_notes_description, Icons.AutoMirrored.Filled.StickyNote2, Color(0xFFD7A928)),
    planned("mac_reminders", ConnectorPlatform.MAC, R.string.connector_apple_reminders_title, R.string.connector_reminders_description, Icons.Filled.NotificationsActive, Color(0xFF4E86C7)),
    planned("mac_desktop", ConnectorPlatform.MAC, R.string.connector_mac_desktop_title, R.string.connector_mac_desktop_description, Icons.Filled.DesktopWindows, Color(0xFF6B7A8F)),
    planned("mac_documents", ConnectorPlatform.MAC, R.string.connector_mac_documents_title, R.string.connector_mac_documents_description, Icons.Filled.Description, Color(0xFF3B96D1)),
    planned("mac_downloads", ConnectorPlatform.MAC, R.string.connector_mac_downloads_title, R.string.connector_mac_downloads_description, Icons.Filled.Download, Color(0xFF7C5AC7)),
)

private val windowsConnectors = listOf(
    planned("windows_files", ConnectorPlatform.WINDOWS, R.string.connector_windows_files_title, R.string.connector_windows_files_description, Icons.Filled.Folder, Color(0xFFB07B2B)),
    planned("windows_calendar", ConnectorPlatform.WINDOWS, R.string.connector_windows_calendar_title, R.string.connector_calendar_description, Icons.Filled.CalendarMonth, Color(0xFF4E86C7)),
    planned("windows_contacts", ConnectorPlatform.WINDOWS, R.string.connector_windows_contacts_title, R.string.connector_contacts_description, Icons.Filled.Contacts, Color(0xFF36A5A8)),
    planned("windows_clipboard", ConnectorPlatform.WINDOWS, R.string.connector_windows_clipboard_title, R.string.connector_windows_clipboard_description, Icons.AutoMirrored.Filled.StickyNote2, Color(0xFF7560B8)),
)

val appConnectorCatalog: List<AppConnectorDefinition> =
    androidConnectors + iosConnectors + macConnectors + windowsConnectors

private fun planned(
    id: String,
    platform: ConnectorPlatform,
    @StringRes titleRes: Int,
    @StringRes descriptionRes: Int,
    icon: ImageVector,
    tint: Color,
): AppConnectorDefinition = AppConnectorDefinition(
    id = id,
    platform = platform,
    titleRes = titleRes,
    descriptionRes = descriptionRes,
    icon = icon,
    tint = tint,
    available = false,
)
