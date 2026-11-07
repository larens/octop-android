package com.octopmobile.app.ui.workbench

import com.octopmobile.app.data.db.MobileSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConnectorCatalogTest {
    @Test
    fun androidCatalogMatchesReleaseOne() {
        val android = appConnectorCatalog.filter { it.platform == ConnectorPlatform.ANDROID }

        assertEquals(
            listOf(
                MobileSourceType.ANDROID_ALARM_TIMER,
                MobileSourceType.ANDROID_USAGE_STATS,
                MobileSourceType.SYSTEM_CALENDAR,
                MobileSourceType.ANDROID_CONTACTS,
                MobileSourceType.ANDROID_FILES,
                MobileSourceType.ANDROID_LOCATION,
                MobileSourceType.ANDROID_NOTIFICATIONS,
                MobileSourceType.ANDROID_SMS,
            ),
            android.map { it.sourceType },
        )
        assertTrue(android.all { it.available })
    }

    @Test
    fun otherPlatformsAreVisibleButNotPretendToBeConnected() {
        val planned = appConnectorCatalog.filter { it.platform != ConnectorPlatform.ANDROID }

        assertTrue(planned.isNotEmpty())
        assertTrue(planned.all { !it.available })
        assertTrue(planned.all { it.sourceType == null })
    }

    @Test
    fun connectorIdsRemainUniqueAcrossPlatforms() {
        val ids = appConnectorCatalog.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertFalse(ids.any { it.isBlank() })
    }
}
