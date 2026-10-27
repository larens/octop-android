package com.octopmobile.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileDataMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OctopDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate3To4_createsMobileDataTablesWithoutLosingThreads() {
        helper.createDatabase(NAME, 3).apply {
            execSQL("INSERT INTO threads VALUES ('thread-1','agent-1','Keep me',NULL,0,1,1,0,NULL,'ask',0,0)")
            close()
        }

        helper.runMigrationsAndValidate(NAME, 4, true, OctopDatabase.MIGRATION_3_4).apply {
            query("SELECT title FROM threads WHERE threadId = 'thread-1'").use {
                check(it.moveToFirst())
                check(it.getString(0) == "Keep me")
            }
            query("SELECT COUNT(*) FROM mobile_outbox").use {
                check(it.moveToFirst())
                check(it.getInt(0) == 0)
            }
            close()
        }
    }

    @Test
    fun migrate4To5_createsTodayCardsWithoutLosingMobileEvents() {
        helper.createDatabase(TODAY_NAME, 4).apply {
            execSQL("INSERT INTO mobile_events VALUES ('event-1','system_calendar',NULL,'calendar.event',1,2,'Calendar','Review','cipher','private_calendar','uploaded',2)")
            close()
        }

        helper.runMigrationsAndValidate(TODAY_NAME, 5, true, OctopDatabase.MIGRATION_4_5).apply {
            query("SELECT summary FROM mobile_events WHERE id = 'event-1'").use {
                check(it.moveToFirst())
                check(it.getString(0) == "Review")
            }
            query("SELECT COUNT(*) FROM today_cards").use {
                check(it.moveToFirst())
                check(it.getInt(0) == 0)
            }
            close()
        }
    }

    @Test
    fun migrate5To6_addsGoalReferencesWithoutLosingTodayCards() {
        helper.createDatabase(GOALS_NAME, 5).apply {
            execSQL("INSERT INTO today_cards VALUES ('card-1','agent-1','research','Title','Summary','Why',0.8,'unread',1,NULL,'Prompt','[]',0,'web_search',NULL,1,1)")
            close()
        }

        helper.runMigrationsAndValidate(GOALS_NAME, 6, true, OctopDatabase.MIGRATION_5_6).apply {
            query("SELECT title, goalId, goalNeedsReview FROM today_cards WHERE id = 'card-1'").use {
                check(it.moveToFirst())
                check(it.getString(0) == "Title")
                check(it.isNull(1))
                check(it.getInt(2) == 0)
            }
            close()
        }
    }

    @Test
    fun migrate6To7_addsExecutionStateWithoutLosingTodayCards() {
        helper.createDatabase(EXECUTION_NAME, 6).apply {
            execSQL("INSERT INTO today_cards VALUES ('card-1','agent-1','research','Title','Summary','Why',0.8,'read',1,NULL,'Prompt','[]',0,'web_search',NULL,NULL,NULL,0,1,1)")
            close()
        }

        helper.runMigrationsAndValidate(EXECUTION_NAME, 7, true, OctopDatabase.MIGRATION_6_7).apply {
            query("SELECT title, executionThreadId, actionState FROM today_cards WHERE id = 'card-1'").use {
                check(it.moveToFirst())
                check(it.getString(0) == "Title")
                check(it.isNull(1))
                check(it.isNull(2))
            }
            close()
        }
    }

    @Test
    fun migrate7To8_addsRiskLevelWithoutLosingTodayCards() {
        helper.createDatabase(RISK_NAME, 7).apply {
            execSQL(
                "INSERT INTO today_cards VALUES (" +
                    "'card-1','agent-1','research','Title','Summary','Why',0.8,'read',1,NULL," +
                    "'Prompt','[]',0,'web_search',NULL,NULL,NULL,0,1,1,NULL,NULL,NULL,NULL,NULL)"
            )
            close()
        }

        helper.runMigrationsAndValidate(RISK_NAME, 8, true, OctopDatabase.MIGRATION_7_8).apply {
            query("SELECT title, riskLevel FROM today_cards WHERE id = 'card-1'").use {
                check(it.moveToFirst())
                check(it.getString(0) == "Title")
                check(it.getString(1) == "A2")
            }
            close()
        }
    }

    private companion object {
        const val NAME = "mobile-data-migration-test"
        const val TODAY_NAME = "today-cards-migration-test"
        const val GOALS_NAME = "today-goals-migration-test"
        const val EXECUTION_NAME = "today-execution-migration-test"
        const val RISK_NAME = "today-risk-migration-test"
    }
}
