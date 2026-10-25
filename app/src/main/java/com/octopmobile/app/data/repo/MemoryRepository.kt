package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.MemoryItemDao
import com.octopmobile.app.data.db.MemoryItemEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 结构化记忆（docs/spec.md §23.3）。
 *
 * 与标签一样，这是**本地优先**的一层：只沉淀用户自己整理过的、可审计的条目
 * （`人物` / `项目` / `偏好` / `承诺` 四类），而不是把聊天记录整段抄进来。
 *
 * 为什么不做服务端同步：上游的记忆能力走 JSON-RPC bridge（`api/routers/memory.py` →
 * `call_memory_rpc`），读的是 `atoms` / `entities` / `episodes` / `candidates` 这套**另一套
 * 模型**，写接口是 `promote` / `reject` / `deprecate` / `replace`，并且整块要
 * `require_permission("memory")`。它和这里有意的四类最小模型不是一个东西，硬做映射只会
 * 让「记忆」页变成服务端内部的镜像视图。所以本版**不接服务端记忆**，`memory_items` 是
 * 用户自己的整理结果；将来要接，也只是在这里多一个 `refresh()`。
 *
 * [MemoryItemEntity.sourceThreadId] / [sourceMessageId] 是来源追溯：条目是从哪个会话、
 * 哪条消息里摘出来的，界面上据此能一键跳回上下文。
 */
class MemoryRepository(private val dao: MemoryItemDao) {

    fun observeAll(): Flow<List<MemoryItemEntity>> = dao.observeAll()

    /** 新建一条记忆。空标题不接受 —— 一条没有标题的记忆在列表上认不出来。 */
    suspend fun create(
        kind: String,
        title: String,
        summary: String,
        sourceThreadId: String? = null,
        sourceMessageId: String? = null,
    ): MemoryItemEntity? {
        val name = title.trim()
        if (name.isEmpty()) return null
        val entity = MemoryItemEntity(
            id = UUID.randomUUID().toString(),
            kind = kind,
            title = name,
            summary = summary.trim(),
            sourceThreadId = sourceThreadId,
            sourceMessageId = sourceMessageId,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        return entity
    }

    /**
     * 修改一条记忆。[sourceThreadId] 沿用原值 —— 改的是内容，不是它从哪来。
     *
     * [id] 找不到时静默返回：那条记忆已经在别处被删掉了，这次编辑没有可落的地方。
     */
    suspend fun update(id: String, kind: String, title: String, summary: String) {
        val name = title.trim()
        if (name.isEmpty()) return
        val existing = dao.find(id) ?: return
        dao.upsert(
            existing.copy(
                kind = kind,
                title = name,
                summary = summary.trim(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: String) {
        dao.delete(id)
    }

    suspend fun clear() {
        dao.clear()
    }
}
