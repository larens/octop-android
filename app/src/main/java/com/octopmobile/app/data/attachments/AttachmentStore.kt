package com.octopmobile.app.data.attachments

import android.content.Context
import com.octopmobile.app.data.db.AttachmentDao
import com.octopmobile.app.data.db.AttachmentEntity
import com.octopmobile.app.data.db.AttachmentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 附件二进制的落盘与容量控制（docs/spec.md §13）。
 *
 * 目录是 `filesDir/attachments/<threadId>/<id>`：按会话分目录，删会话时可以整目录带走；
 * 文件名就是引用哈希（[AttachmentRefs.idOf]），所以同一张图不会存两份。
 *
 * **淘汰按写入时间从早到晚**（先写进来的先被清）。没有做严格的 LRU：那需要在每次显示图片时
 * 回写访问时间，一次文件系统写入换来的收益不值得，而容量上限才是这个机制真正要保证的东西。
 */
class AttachmentStore(
    context: Context,
    private val dao: AttachmentDao,
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
) {

    private val root: File = File(context.filesDir, ROOT_DIR)

    /** 落盘 + 记一行索引，返回本地文件。写完立刻跑一次容量控制。 */
    suspend fun store(
        threadId: String,
        id: String,
        remoteUrl: String,
        mime: String?,
        bytes: ByteArray,
    ): File = withContext(Dispatchers.IO) {
        val file = File(dirFor(threadId), id)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)

        val existing = dao.find(id)
        dao.upsertAll(
            listOf(
                AttachmentEntity(
                    id = id,
                    threadId = threadId,
                    // REPLACE 语义下不显式带回来，历史消息给的归属就会被写成 null。
                    messageId = existing?.messageId,
                    remoteUrl = remoteUrl,
                    localPath = file.absolutePath,
                    mime = mime ?: existing?.mime,
                    size = bytes.size.toLong(),
                    state = AttachmentState.CACHED,
                ),
            ),
        )
        enforceBudgetLocked()
        file
    }

    /** 本地已占用的字节数，设置页展示用。 */
    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 一键清理（docs/spec.md §13、§19 第 19 条）。
     *
     * 只删附件二进制与索引，**不动**登录态、会话与消息 —— 所以清完 App 仍然可用，
     * 已渲染的图片会按需重新拉取。
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        root.deleteRecursively()
        dao.clear()
    }

    /** 只在已经跑在 [Dispatchers.IO] 的调用里用（[store] 内部）。 */
    private suspend fun enforceBudgetLocked() {
        val files = root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .toList()

        var total = files.sumOf { it.length() }
        var index = 0
        while (total > budgetBytes && index < files.size) {
            val file = files[index++]
            val size = file.length()
            if (file.delete()) {
                total -= size
                dao.markEvicted(file.name)
            }
        }
    }

    private fun dirFor(threadId: String): File = File(root, threadId.replace('/', '_'))

    private companion object {
        const val ROOT_DIR = "attachments"
    }
}

/** 附件缓存总容量上限（docs/spec.md §13：默认 200 MB）。 */
private const val DEFAULT_BUDGET_BYTES = 200L * 1024 * 1024
