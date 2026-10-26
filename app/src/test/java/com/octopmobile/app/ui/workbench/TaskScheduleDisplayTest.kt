package com.octopmobile.app.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskScheduleDisplayTest {
    @Test
    fun rendersCommonCronSchedulesAsNaturalLanguage() {
        assertEquals("每天 09:00", humanSchedule("cron:0 9 * * *"))
        assertEquals("每周日 20:00", humanSchedule("cron:0 20 * * 0"))
        assertEquals("每周一 09:30", humanSchedule("cron:30 9 * * 1"))
        assertEquals("每月 1 日 07:30", humanSchedule("cron:30 7 1 * *"))
    }

    @Test
    fun preservesUnknownSchedule() {
        assertEquals("cron:*/15 * * * *", humanSchedule("cron:*/15 * * * *"))
    }
}
