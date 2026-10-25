package com.octopmobile.app.data.repo

import java.util.Calendar
import java.util.TimeZone

/**
 * 服务端 cron 触发规则（docs/protocol.md §5.5）的本地解析。
 *
 * 规则原文形如 `cron:0 9 * * *` / `interval:3600` / `date:2030-01-01T08:00:00` / `agently:xxx`，
 * 由上游 `infra/cron/trigger.py:build_trigger` 消费。**执行一律在服务端**，这里只做两件事：
 *
 * 1. 把规则翻译成界面能显示的样子；
 * 2. **本地估算**下次执行时间（列表上的「下次」）。
 *
 * 第 2 条刻意做成「估算」：服务端按 `config.default_timezone` 解释挂钟时间，客户端只能按设备
 * 时区算，两者不一致时会差几小时。所以这个值不参与任何逻辑，只用于展示。
 */
sealed interface ScheduleRule {

    /** `cron:分 时 日 月 周`（Unix crontab 语义，周字段 0/7 = 周日）。 */
    data class Cron(val fields: List<String>) : ScheduleRule

    /** `interval:秒`。 */
    data class Interval(val seconds: Long) : ScheduleRule

    /** `date:ISO 时间`；时间串解析失败时为 null。 */
    data class Once(val epochMillis: Long?) : ScheduleRule

    /** `agently:<连接器实例 id>`：由外部事件（新邮件）触发，不落在挂钟上。 */
    data class Event(val instanceId: String) : ScheduleRule

    /** 读不懂的规则。原样保留，界面直接显示原文。 */
    data class Unknown(val raw: String) : ScheduleRule
}

object ScheduleRules {

    private const val KIND_CRON = "cron"
    private const val KIND_INTERVAL = "interval"
    private const val KIND_DATE = "date"
    private const val KIND_EVENT = "agently"

    /** 找下次执行时间时的搜索上限：一年。超出就认为「很久以后」，返回 null 交给界面显示规则原文。 */
    private const val SEARCH_DAYS = 366

    fun parse(raw: String): ScheduleRule {
        val spec = raw.trim()
        val kind = spec.substringBefore(':', missingDelimiterValue = "")
        val value = spec.substringAfter(':', missingDelimiterValue = "").trim()
        if (kind.isEmpty() || value.isEmpty()) return ScheduleRule.Unknown(spec)

        return when (kind) {
            KIND_INTERVAL -> value.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { ScheduleRule.Interval(it) }
                ?: ScheduleRule.Unknown(spec)

            KIND_CRON -> value.split(Regex("\\s+"))
                .takeIf { it.size == 5 }
                ?.let { ScheduleRule.Cron(it) }
                ?: ScheduleRule.Unknown(spec)

            KIND_DATE -> ScheduleRule.Once(value.toEpochMillisOrNull())

            KIND_EVENT -> ScheduleRule.Event(value)

            else -> ScheduleRule.Unknown(spec)
        }
    }

    /**
     * 估算 [rule] 在 [fromMillis] 之后的下一次触发时间；算不出来返回 null。
     *
     * [lastRunMillis] 只对 `date:` 有意义：一次性任务跑过就不该再显示「下次」。
     */
    fun nextRunAt(
        rule: ScheduleRule,
        fromMillis: Long,
        lastRunMillis: Long? = null,
    ): Long? = when (rule) {
        is ScheduleRule.Interval -> fromMillis + rule.seconds * 1000

        is ScheduleRule.Once -> rule.epochMillis?.takeIf { it > fromMillis && (lastRunMillis ?: 0) < it }

        is ScheduleRule.Cron -> nextCronMatch(rule.fields, fromMillis)

        // 事件驱动的任务没有可预测的下次时间。
        is ScheduleRule.Event, is ScheduleRule.Unknown -> null
    }

    // ------------------------------------------------------------ cron 求值

    private fun nextCronMatch(fields: List<String>, fromMillis: Long): Long? {
        val minutes = expand(fields[0], 0, 59)?.sorted() ?: return null
        val hours = expand(fields[1], 0, 23)?.sorted() ?: return null
        val daysOfMonth = expand(fields[2], 1, 31) ?: return null
        val months = expand(fields[3], 1, 12) ?: return null
        // Unix crontab 的周是 0/7 = 周日、1 = 周一；本地 `Calendar` 是 1 = 周日，差一。
        val daysOfWeek = expand(fields[4], 0, 7)?.map { it % 7 }?.toSet() ?: return null

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = fromMillis
        // 从下一分钟开始找：当前这一分钟即使匹配也已经是「过去」了。
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.MINUTE, 1)

        val wildcardDom = fields[2] == "*"
        val wildcardDow = fields[4] == "*"

        // 按天推进、再在匹配的那天里挑时刻：整天下不匹配就一次跳一天，
        // 避免逐分钟扫描（`0 9 1 1 *` 这类规则要扫一整年）。
        repeat(SEARCH_DAYS) { index ->
            val month = calendar.get(Calendar.MONTH) + 1
            val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
            // `Calendar.DAY_OF_WEEK`：1 = 周日 … 7 = 周六 → 转成 0 = 周日。
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1

            // 日与周都写死时是「或」的关系，这是 crontab 的既有语义。
            val dayMatches = when {
                wildcardDom && wildcardDow -> true
                wildcardDom -> dayOfWeek in daysOfWeek
                wildcardDow -> dayOfMonth in daysOfMonth
                else -> dayOfMonth in daysOfMonth || dayOfWeek in daysOfWeek
            }

            if (month in months && dayMatches) {
                // 第一天要接着「下一分钟」往后找，之后每天从 00:00 起。
                val fromHour = if (index == 0) calendar.get(Calendar.HOUR_OF_DAY) else 0
                val fromMinute = if (index == 0) calendar.get(Calendar.MINUTE) else 0
                val hour = hours.firstOrNull { it > fromHour || (it == fromHour && minutes.any { m -> m >= fromMinute }) }
                val minute = hour?.let { h ->
                    minutes.firstOrNull { m -> h > fromHour || m >= fromMinute }
                }
                if (hour != null && minute != null) {
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                    return calendar.timeInMillis
                }
            }

            calendar.add(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
        }
        return null
    }

    /**
     * 展开一个 cron 字段为允许值集合。支持 `*`、单值、`a-b` 区间、带 `/step` 的步长写法、
     * 逗号列表与三字母月份/星期缩写；看不懂就返回 null（调用方转而显示规则原文）。
     */
    private fun expand(field: String, min: Int, max: Int): Set<Int>? {
        val result = mutableSetOf<Int>()
        for (part in field.split(',')) {
            val item = part.trim()
            if (item.isEmpty()) return null

            val pieces = item.split('/')
            if (pieces.size > 2) return null
            val rangeText = pieces[0]
            val stepText = pieces.getOrNull(1)
            val step = when {
                stepText == null -> 1
                else -> stepText.toIntOrNull()?.takeIf { it > 0 } ?: return null
            }

            val first: Int
            val last: Int
            when {
                rangeText == "*" -> {
                    first = min
                    last = max
                }

                '-' in rangeText -> {
                    val bounds = rangeText.split('-')
                    if (bounds.size != 2) return null
                    first = bounds[0].toFieldValue(min, max) ?: return null
                    last = bounds[1].toFieldValue(min, max) ?: return null
                }

                else -> {
                    val single = rangeText.toFieldValue(min, max) ?: return null
                    // 单值带步长（`5/10`）在 crontab 里表示「从 5 开始每 10 一次」，而不是只有 5。
                    first = single
                    last = if (stepText == null) single else max
                }
            }
            if (first > last) return null
            var value = first
            while (value <= last) {
                result += value
                value += step
            }
        }
        return result
    }

    /** 数字与三字母缩写（`jan` / `mon`）都接受；越界返回 null。 */
    private fun String.toFieldValue(min: Int, max: Int): Int? {
        val text = trim().lowercase()
        if (text.isEmpty()) return null
        val numeric = text.toIntOrNull()
        if (numeric != null) return numeric.takeIf { it in min..max }
        val names = if (max == 12) MONTH_NAMES else WEEKDAY_NAMES
        val index = names.indexOf(text)
        return if (index < 0) null else (index + if (max == 12) 1 else 0)
    }

    /** `date:` 接受 ISO-8601 本地时间（可带时区），与上游 `datetime.fromisoformat` 对齐。 */
    private fun String.toEpochMillisOrNull(): Long? = runCatching {
        java.time.OffsetDateTime.parse(this).toInstant().toEpochMilli()
    }.recoverCatching {
        java.time.LocalDateTime.parse(this)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.recoverCatching {
        java.time.LocalDate.parse(this)
            .atStartOfDay(TimeZone.getDefault().toZoneId())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private val MONTH_NAMES = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    )

    /** 与上游 `_UNIX_WEEKDAYS` 一致：0 = 周日。 */
    private val WEEKDAY_NAMES = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
}
