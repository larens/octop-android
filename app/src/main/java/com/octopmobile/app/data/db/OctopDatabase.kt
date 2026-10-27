package com.octopmobile.app.data.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.withTransaction

/**
 * 本地缓存库。
 *
 * **不使用 `fallbackToDestructiveMigration`** —— 那等于用户本地会话被静默清空（docs/spec.md §8.4）。
 * schema 导出到 `app/schemas/` 并提交进仓库，每次改表必须附迁移 + 一条 JVM 可跑的迁移测试。
 */
@Database(
    entities = [
        InstanceEntity::class,
        ThreadEntity::class,
        ThreadTagEntity::class,
        MemoryItemEntity::class,
        AutomationTaskEntity::class,
        MessageEntity::class,
        CompactMarkerEntity::class,
        AttachmentEntity::class,
        PendingOpEntity::class,
        MobileSourceEntity::class,
        MobileEventEntity::class,
        MobileOutboxEntity::class,
        MobileSyncStateEntity::class,
        TodayCardEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class OctopDatabase : RoomDatabase() {

    abstract fun instances(): InstanceDao
    abstract fun threads(): ThreadDao
    abstract fun threadTags(): ThreadTagDao
    abstract fun memoryItems(): MemoryItemDao
    abstract fun automationTasks(): AutomationTaskDao
    abstract fun messages(): MessageDao
    abstract fun attachments(): AttachmentDao
    abstract fun compactMarkers(): CompactMarkerDao
    abstract fun pendingOps(): PendingOpDao
    abstract fun mobileSources(): MobileSourceDao
    abstract fun mobileEvents(): MobileEventDao
    abstract fun mobileOutbox(): MobileOutboxDao
    abstract fun mobileSyncState(): MobileSyncStateDao
    abstract fun todayCards(): TodayCardDao

    /**
     * 清空本地镜像。退出登录时调用（docs/spec.md §15）—— 换个账号登录不该看到上一个账号的会话。
     * 只删数据、不动 schema，所以不需要迁移。
     */
    suspend fun clearAll() {
        withTransaction {
            messages().clear()
            threads().clear()
            threadTags().clear()
            memoryItems().clear()
            automationTasks().clear()
            attachments().clear()
            compactMarkers().clear()
            pendingOps().clear()
            mobileOutbox().clear()
            mobileEvents().clear()
            mobileSyncState().clear()
            mobileSources().clear()
            todayCards().clear()
            instances().clear()
        }
    }

    companion object {
        private const val NAME = "octop.db"

        /**
         * v2：为 Today 化升级预埋三张表——会话标签、结构化记忆、自动任务。
         *
         * 这一步只加本地结构，不改现有读写路径，因此迁移保持纯建表；真正的同步与 UI
         * 会在后续阶段逐层接上。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `thread_tags` (
                        `threadId` TEXT NOT NULL,
                        `tag` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`threadId`, `tag`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_thread_tags_tag` ON `thread_tags` (`tag`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_thread_tags_kind` ON `thread_tags` (`kind`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_items` (
                        `id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `sourceThreadId` TEXT,
                        `sourceMessageId` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_items_kind` ON `memory_items` (`kind`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_items_sourceThreadId` ON `memory_items` (`sourceThreadId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_items_updatedAt` ON `memory_items` (`updatedAt`)",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `automation_tasks` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `scheduleRule` TEXT NOT NULL,
                        `scopeKind` TEXT NOT NULL,
                        `scopeRef` TEXT,
                        `status` TEXT NOT NULL,
                        `lastRunAt` INTEGER,
                        `nextRunAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_automation_tasks_status` ON `automation_tasks` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_automation_tasks_scopeKind` ON `automation_tasks` (`scopeKind`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_automation_tasks_scopeRef` ON `automation_tasks` (`scopeRef`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_automation_tasks_nextRunAt` ON `automation_tasks` (`nextRunAt`)",
                )
            }
        }

        @Volatile
        private var instance: OctopDatabase? = null

        /**
         * v3：把 `automation_tasks` 对齐服务端 cron 的真实字段（多 agent 归属、上次执行状态）。
         *
         * 这张表在 v2 里只是「本地建模」、没有任何写入路径，所以直接整表重建 ——
         * 服务端 cron 列表是全量接口，下次打开任务页就会重新灌满。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `automation_tasks`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `automation_tasks` (
                        `id` TEXT NOT NULL,
                        `agentId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `scheduleRule` TEXT NOT NULL,
                        `scopeRef` TEXT,
                        `status` TEXT NOT NULL,
                        `lastRunAt` INTEGER,
                        `lastStatus` TEXT,
                        `lastError` TEXT,
                        `nextRunAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_automation_tasks_agentId` ON `automation_tasks` (`agentId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_automation_tasks_status` ON `automation_tasks` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_automation_tasks_nextRunAt` ON `automation_tasks` (`nextRunAt`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `mobile_sources` (`sourceType` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `permissionState` TEXT NOT NULL, `lastSyncAt` INTEGER, `lastError` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sourceType`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mobile_sources_enabled` ON `mobile_sources` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mobile_sources_updatedAt` ON `mobile_sources` (`updatedAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `mobile_events` (`id` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceRecordId` TEXT, `eventType` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `observedAt` INTEGER NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `privacyClass` TEXT NOT NULL, `uploadState` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mobile_events_sourceType` ON `mobile_events` (`sourceType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mobile_events_occurredAt` ON `mobile_events` (`occurredAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mobile_events_uploadState` ON `mobile_events` (`uploadState`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `mobile_outbox` (`eventId` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `lastAttemptAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`eventId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mobile_outbox_createdAt` ON `mobile_outbox` (`createdAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `mobile_sync_state` (`sourceType` TEXT NOT NULL, `cursor` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`sourceType`))")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `today_cards` (`id` TEXT NOT NULL, `agentId` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, `whyNow` TEXT NOT NULL, `confidence` REAL NOT NULL, `lifecycle` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `snoozedUntil` INTEGER, `actionPrompt` TEXT NOT NULL, `evidenceJson` TEXT NOT NULL, `evidenceCount` INTEGER NOT NULL, `evidenceSources` TEXT NOT NULL, `sourceUrl` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_today_cards_agentId` ON `today_cards` (`agentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_today_cards_lifecycle` ON `today_cards` (`lifecycle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_today_cards_updatedAt` ON `today_cards` (`updatedAt`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `today_cards` ADD COLUMN `goalId` TEXT")
                db.execSQL("ALTER TABLE `today_cards` ADD COLUMN `goalTitle` TEXT")
                db.execSQL("ALTER TABLE `today_cards` ADD COLUMN `goalNeedsReview` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `today_cards` ADD COLUMN `executionThreadId` TEXT")
                db.execSQL("ALTER TABLE `today_cards` ADD COLUMN `executionTurnId` TEXT")
                db.execSQL("ALTER TABLE `today_cards` ADD COLUMN `actionState` TEXT")
                db.execSQL("ALTER TABLE `today_cards` ADD COLUMN `actionError` TEXT")
                db.execSQL("ALTER TABLE `today_cards` ADD COLUMN `actionUpdatedAt` INTEGER")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `today_cards` ADD COLUMN `riskLevel` TEXT NOT NULL DEFAULT 'A2'")
            }
        }

        fun get(context: Context): OctopDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OctopDatabase::class.java,
                    NAME,
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                    .build()
                    .also { instance = it }
            }
    }
}
