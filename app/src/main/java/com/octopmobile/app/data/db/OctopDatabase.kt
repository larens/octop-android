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
    ],
    version = 3,
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

        fun get(context: Context): OctopDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OctopDatabase::class.java,
                    NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
