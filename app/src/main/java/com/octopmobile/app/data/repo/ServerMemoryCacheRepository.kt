package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.ServerMemoryCacheDao
import com.octopmobile.app.data.db.ServerMemoryCacheEntity
import kotlinx.serialization.json.Json

data class CachedServerMemorySnapshot(
    val snapshot: ServerMemorySnapshot,
    val cachedAt: Long,
)

/** 服务端记忆的只读展示缓存。解码失败或数据库异常时按无缓存处理，不影响在线加载。 */
class ServerMemoryCacheRepository(
    private val dao: ServerMemoryCacheDao,
    private val json: Json,
) {

    suspend fun read(host: String, agentId: String): CachedServerMemorySnapshot? {
        val entity = runCatching { dao.find(host, agentId) }.getOrNull() ?: return null
        if (entity.schemaVersion != CACHE_SCHEMA_VERSION) return null
        val snapshot = runCatching {
            json.decodeFromString(ServerMemorySnapshot.serializer(), entity.snapshotJson)
        }.getOrNull() ?: return null
        return CachedServerMemorySnapshot(snapshot = snapshot, cachedAt = entity.cachedAt)
    }

    suspend fun write(
        host: String,
        agentId: String,
        snapshot: ServerMemorySnapshot,
        cachedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        return runCatching {
            dao.upsert(
                ServerMemoryCacheEntity(
                    host = host,
                    agentId = agentId,
                    schemaVersion = CACHE_SCHEMA_VERSION,
                    snapshotJson = json.encodeToString(ServerMemorySnapshot.serializer(), snapshot),
                    cachedAt = cachedAt,
                ),
            )
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val CACHE_SCHEMA_VERSION = 1
    }
}
