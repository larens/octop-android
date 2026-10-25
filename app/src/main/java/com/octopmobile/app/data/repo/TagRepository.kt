package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.ThreadTagDao
import com.octopmobile.app.data.db.ThreadTagEntity
import kotlinx.coroutines.flow.Flow

/**
 * 会话标签（docs/spec.md §23.3）。
 *
 * **这是一个纯本地概念，没有服务端同步对象。** 上游 Octop 没有任何 thread tag 接口 ——
 * `ThreadDto` 里没有标签字段，路由表里也没有 `/tags`，全仓 grep `tag` 只命中 OpenAPI 的接口
 * 分组标签与 subagent 提示词。所以标签是「这台设备上把会话挂到项目 / 主题下」的整理层，
 * 存在 Room 里，卸载即失。
 *
 * 与其它仓储的差别要写在明处：这里**没有**「服务端先成功再写本地」那一步，因为根本没有
 * 服务端那一侧。写库即生效，`Flow` 立刻把新状态推给界面。
 */
class TagRepository(private val tagDao: ThreadTagDao) {

    fun observeAll(): Flow<List<ThreadTagEntity>> = tagDao.observeAll()

    fun observeForThread(threadId: String): Flow<List<ThreadTagEntity>> =
        tagDao.observeByThread(threadId)

    /**
     * 给会话打一个标签。主键是 `threadId + tag`，所以重复打同一个标签是幂等的
     * （会刷新 [ThreadTagEntity.createdAt]，无副作用）。
     */
    suspend fun add(threadId: String, tag: String, kind: String) {
        val name = tag.trim()
        if (name.isEmpty()) return
        tagDao.upsert(
            ThreadTagEntity(
                threadId = threadId,
                tag = name,
                kind = kind,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun remove(threadId: String, tag: String) {
        tagDao.deleteOne(threadId, tag)
    }

    /** 删会话时一并清掉它的标签，否则会留下永远指不到会话的孤儿行。 */
    suspend fun deleteForThread(threadId: String) {
        tagDao.deleteByThread(threadId)
    }

    suspend fun clear() {
        tagDao.clear()
    }
}
