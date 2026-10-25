package com.octopmobile.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        MessageEntity::class,
        CompactMarkerEntity::class,
        AttachmentEntity::class,
        PendingOpEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OctopDatabase : RoomDatabase() {

    abstract fun instances(): InstanceDao
    abstract fun threads(): ThreadDao
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
            attachments().clear()
            compactMarkers().clear()
            pendingOps().clear()
            instances().clear()
        }
    }

    companion object {
        private const val NAME = "octop.db"

        @Volatile
        private var instance: OctopDatabase? = null

        fun get(context: Context): OctopDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OctopDatabase::class.java,
                    NAME,
                ).build().also { instance = it }
            }
    }
}
