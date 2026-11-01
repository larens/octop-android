package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.ServerMemoryCacheDao
import com.octopmobile.app.data.db.ServerMemoryCacheEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServerMemoryCacheRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun roundTrip_preservesSnapshotAndCachedTime() = runBlocking {
        val dao = FakeServerMemoryCacheDao()
        val repository = ServerMemoryCacheRepository(dao, json)
        val snapshot = ServerMemorySnapshot(
            portrait = MemoryPortrait(
                preferredAddress = "老师",
                summary = "画像正文",
                brief = "画像摘要",
                focus = listOf("记忆缓存"),
                truths = listOf("喜欢先看结论"),
            ),
            episodes = listOf(
                MemoryEpisode(
                    id = "diary-1",
                    title = "把缓存补上",
                    emotion = "reflective",
                    summary = "今天补上了记忆页的离线缓存。",
                    insight = "断网时也应该能回看历史。",
                    intensity = 3,
                    occurredAt = 1_793_000_000_000,
                ),
            ),
            archives = listOf(
                MemoryArchiveItem(
                    kind = "project",
                    title = "Octop Android",
                    detail = "记忆页本地缓存",
                    updatedAt = 1_793_000_100_000,
                ),
            ),
        )

        assertEquals(true, repository.write("https://octop.example", "agent-1", snapshot, 1234L))

        val cached = repository.read("https://octop.example", "agent-1")
        assertNotNull(cached)
        assertEquals(1234L, cached!!.cachedAt)
        assertEquals(snapshot, cached.snapshot)
    }

    @Test
    fun corruptPayload_isTreatedAsMissingCache() = runBlocking {
        val dao = FakeServerMemoryCacheDao(
            stored = ServerMemoryCacheEntity(
                host = "https://octop.example",
                agentId = "agent-1",
                schemaVersion = 1,
                snapshotJson = """{"portrait":""",
                cachedAt = 1234L,
            ),
        )
        val repository = ServerMemoryCacheRepository(dao, json)

        assertNull(repository.read("https://octop.example", "agent-1"))
    }

    private class FakeServerMemoryCacheDao(
        var stored: ServerMemoryCacheEntity? = null,
    ) : ServerMemoryCacheDao {
        override suspend fun find(host: String, agentId: String): ServerMemoryCacheEntity? =
            stored?.takeIf { it.host == host && it.agentId == agentId }

        override suspend fun upsert(entity: ServerMemoryCacheEntity) {
            stored = entity
        }

        override suspend fun delete(host: String, agentId: String) {
            if (stored?.host == host && stored?.agentId == agentId) stored = null
        }

        override suspend fun clear() {
            stored = null
        }
    }
}
