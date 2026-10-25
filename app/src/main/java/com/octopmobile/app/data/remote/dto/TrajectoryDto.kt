package com.octopmobile.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 轨迹（trajectory）分页响应（`routers/chat/trajectory.py:60-64`）。
 *
 * 轨迹是服务端对一轮轮执行过程的**事件流**投影（`user` / `assistant` / `tool` / `context` /
 * `compacted` / `system`），比 `/history` 的消息视图更底层。客户端只用它取 [COMPACTED_KIND]
 * 一种事件 —— 消息流本身仍然以 `/history` + WS 为准。
 */
@Serializable
data class TrajectoryHistoryDto(
    @SerialName("thread_id") val threadId: String? = null,
    val events: List<TrajectoryEventDto> = emptyList(),
    /** 继续往前翻的游标（`events[0].seq`），`null` 表示到头了。 */
    @SerialName("next_before_seq") val nextBeforeSeq: Int? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

/**
 * 单条轨迹事件（`routers/chat/trajectory.py:46-57`）。
 *
 * `kind` 取值是 `user` / `assistant` / `tool` / `context` / `compacted` / `system` / `unknown`
 * （`trajectory/types.py:7-15`），客户端只消费其中一种，所以按字符串处理而不做枚举建模。
 *
 * 只声明真正用到的字段：`payload`（`summarized_count` / `removed_tokens` 等）在列表视图里
 * 会被裁剪，且对画一条分隔线没有用处，所以不做建模。
 */
@Serializable
data class TrajectoryEventDto(
    val seq: Int = 0,
    /** epoch **秒**，带小数（上游 `ts: float`）。 */
    val ts: Double = 0.0,
    val kind: String? = null,
    /** 服务端已裁剪到 240 字符以内的事件摘要（`trajectory.py:117-118`）。 */
    val summary: String? = null,
)

object TrajectoryKind {
    /** 上下文压缩点。**唯一被客户端消费的 kind**（docs/spec.md §8.2）。 */
    const val COMPACTED = "compacted"
}
