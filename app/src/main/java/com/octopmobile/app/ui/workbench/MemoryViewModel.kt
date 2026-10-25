package com.octopmobile.app.ui.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.data.db.MemoryItemEntity
import com.octopmobile.app.data.repo.MemoryRepository
import com.octopmobile.app.ui.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 结构化记忆（docs/spec.md §23.3）。
 *
 * 没有网络、没有服务端对象，也不该有加载态 —— 全部是本地库的写与读，
 * Flow 立刻把新状态推给界面（原因见 [MemoryRepository] 顶部）。
 */
class MemoryViewModel(private val container: AppContainer) : ViewModel() {

    val items: StateFlow<List<MemoryItemEntity>> = container.memoryRepository().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 新建 / 修改一条记忆，三种情形合成一个入口：
     *
     * - [id] 非空 → 改已有那条（[sourceThreadId] 沿用原值，改的是内容不是来源）；
     * - [id] 为空、[sourceThreadId] 非空 → 从会话里存下来，带上来源；
     * - 都为空 → 记忆页里手写的新条目。
     */
    fun save(
        id: String?,
        kind: String,
        title: String,
        summary: String,
        sourceThreadId: String? = null,
    ) {
        viewModelScope.launch {
            val repository: MemoryRepository = container.memoryRepository()
            when {
                id != null -> repository.update(id, kind, title, summary)
                sourceThreadId != null -> repository.create(kind, title, summary, sourceThreadId = sourceThreadId)
                else -> repository.create(kind, title, summary)
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { container.memoryRepository().delete(id) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MemoryViewModel(appContainer()) }
        }
    }
}
