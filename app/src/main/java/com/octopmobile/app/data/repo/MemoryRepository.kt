package com.octopmobile.app.data.repo

import com.octopmobile.app.data.db.MemoryItemDao
import com.octopmobile.app.data.db.MemoryItemEntity
import com.octopmobile.app.data.remote.ApiResult
import com.octopmobile.app.data.remote.OctopApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.time.Instant
import java.time.ZoneId

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

/**
 * 服务端记忆聚合。
 *
 * 上游已经把「关于我 / 当前关注 / 你告诉过我的 / 情绪 episode / 原子记忆」拆成多条接口；
 * 手机端不把它们逐个照搬成后台视图，而是聚合成适合 Today 风格卡片页的三块：
 *
 * 1. 用户画像
 * 2. 情绪日记
 * 3. 记忆沉淀
 *
 * 所有解析都走**宽松兜底**：`octop-memory` 返回的是 JSON-RPC 透传结构，不同版本字段可能
 * 微调，所以这里尽量按语义提取文本，而不是强绑一套脆弱 DTO。
 */
class ServerMemoryRepository(private val api: OctopApi) {

    suspend fun snapshot(
        agentId: String,
        refreshDiaries: Boolean = false,
    ): ApiResult<ServerMemorySnapshot> {
        return supervisorScope {
            val aboutDeferred = async { api.memoryTerminalAboutMe(agentId) }
            val focusDeferred = async { api.memoryTerminalCurrentFocus(agentId).valueOrNull() }
            val toldDeferred = async { api.memoryTerminalThingsYouToldMe(agentId).valueOrNull() }
            val entityDeferred = async { api.memoryTerminalEntities(agentId).valueOrNull() }
            val episodeDeferred = async { api.memoryEpisodes(agentId).valueOrNull() }
            val atomDeferred = async { api.memoryAtoms(agentId).valueOrNull() }
            val diaryDeferred = async {
                api.memoryDiaries(agentId, refresh = refreshDiaries).valueOrNull()
            }

            val about = aboutDeferred.await()
            if (about is ApiResult.Failure) return@supervisorScope about
            val aboutValue = (about as ApiResult.Ok).value

            val extras = awaitAll(
                focusDeferred,
                toldDeferred,
                entityDeferred,
                episodeDeferred,
                atomDeferred,
                diaryDeferred,
            )

            val focus = extras[0]
            val told = extras[1]
            val entities = extras[2]
            val episodes = extras[3]
            val atoms = extras[4]
            val diaries = extras[5]
            val portrait = buildPortrait(aboutValue, focus, told)
            val archives = buildArchives(entities, atoms, portrait)

            ApiResult.Ok(
                ServerMemorySnapshot(
                    portrait = portrait,
                    episodes = parseDiaries(diaries).ifEmpty { parseEpisodes(episodes) },
                    archives = archives,
                ),
            )
        }
    }

    private fun buildPortrait(
        about: JsonElement,
        focus: JsonElement?,
        told: JsonElement?,
    ): MemoryPortrait {
        val aboutTexts = extractProfileTexts(about).filter(::looksLikeProfileText)
        val toldTexts = extractProfileTexts(told, excludeScheduledTasks = true)
            .filter(::looksLikeProfileText)
        val profileSubject = extractProfileSubject(about)
        val summary = extractProfileNarrative(about, profileSubject)
            .ifBlank {
                aboutTexts
                    .ifEmpty { toldTexts }
                    .take(PORTRAIT_SUMMARY_MAX)
                    .joinToString(separator = "\n\n")
                    .let(::cleanProfileDisplayText)
                    .trim()
            }
        val focusItems = extractProfileTexts(focus, excludeScheduledTasks = true)
            .ifEmpty { deriveHighlights(aboutTexts + toldTexts, onlyFocus = true) }
            .take(PORTRAIT_LIST_MAX)
        val truthItems = toldTexts
            .ifEmpty { deriveHighlights(aboutTexts, onlyFocus = false) }
            .take(PORTRAIT_LIST_MAX)

        return MemoryPortrait(
            preferredAddress = extractPreferredAddress(about, told),
            summary = summary,
            brief = extractProfileBrief(about).ifBlank { compactPortraitPreview(summary) },
            focus = focusItems,
            truths = truthItems,
        )
    }

    private fun extractProfileNarrative(about: JsonElement, subject: String?): String {
        val payload = about as? JsonObject ?: return ""
        val generated = firstText(payload, "profile_narrative")
        if (!generated.isNullOrBlank()) return cleanProfileNarrative(generated, subject = null)
        val raw = firstText(payload, "profile_markdown", "about_me")
            ?: return ""
        return cleanProfileNarrative(raw, subject)
    }

    private fun extractProfileBrief(about: JsonElement): String {
        val payload = about as? JsonObject ?: return ""
        return firstText(payload, "profile_brief")
            ?.let { cleanProfileNarrative(it, subject = null) }
            .orEmpty()
    }

    private fun compactPortraitPreview(summary: String): String {
        val compact = summary.trim()
        if (compact.isEmpty()) return ""
        val paragraphs = compact.split(Regex("\\n\\s*\\n")).map(String::trim).filter(String::isNotEmpty)
        val preview = paragraphs.take(2).joinToString("\n\n")
        return if (preview.length <= PORTRAIT_BRIEF_MAX) {
            preview
        } else {
            preview.take(PORTRAIT_BRIEF_MAX).trimEnd('，', '。', ',', '.') + "…"
        }
    }

    private fun extractProfileSubject(about: JsonElement): String? {
        val payload = about as? JsonObject ?: return null
        return firstText(payload, "profile_subject", "canonical_name", "entity_name")
            ?.takeIf { it.length in 1..40 }
    }

    private fun cleanProfileNarrative(raw: String, subject: String?): String {
        var narrative = raw
            .replace("&#10;", "\n")
            .replace("&amp;#10;", "\n")
            .replace("\\n", "\n")
            .replace(Regex("\\s*\\((?:atom-)?[0-9a-f]{6,}(?:-[0-9a-f]+)*\\)"), "")
            .replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*]\\s+"), "")
            .replace("**", "")
            .replace("\r", "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (!subject.isNullOrBlank()) {
            narrative = narrative.replace(subject, "你")
        }
        return narrative
    }

    private fun extractProfileTexts(
        payload: JsonElement?,
        excludeScheduledTasks: Boolean = false,
    ): List<String> {
        val texts = itemObjects(payload)
            .filterNot { excludeScheduledTasks && isScheduledTask(it) }
            .mapNotNull { item ->
                firstText(
                    item,
                    "assertion",
                    "summary",
                    "text",
                    "content",
                    "description",
                    "value",
                    "fact",
                    "claim",
                    "story",
                    "verbatim_quote",
                )
            }
            .flatMap(::splitReadableText)
            .distinct()
        return texts.ifEmpty {
            extractTexts(payload)
                .filterNot { excludeScheduledTasks && looksLikeScheduledTaskText(it) }
        }
    }

    private fun isScheduledTask(item: JsonObject): Boolean {
        val text = listOf(
            "assertion",
            "summary",
            "text",
            "content",
            "description",
            "value",
            "fact",
            "claim",
            "story",
            "verbatim_quote",
        ).mapNotNull { key -> firstText(item, key) }
            .joinToString(" ")
        return looksLikeScheduledTaskText(text)
    }

    private fun looksLikeScheduledTaskText(text: String): Boolean {
        val value = text.lowercase()
        return listOf(
            "定时任务",
            "触发规则",
            "cron:",
            "cron job",
            "scheduled task",
            "schedule rule",
        ).any(value::contains)
    }

    private fun extractPreferredAddress(about: JsonElement, told: JsonElement?): String? {
        val payloads = listOfNotNull(about, told)
        val structuredKeys = listOf(
            "preferred_address",
            "preferred_name",
            "address_me_as",
            "call_me",
            "salutation",
            "nickname",
            "称呼我",
            "惯用称呼",
        )
        payloads.asSequence()
            .flatMap { itemObjects(it).asSequence() }
            .firstNotNullOfOrNull { item -> firstText(item, *structuredKeys.toTypedArray()) }
            ?.let(::cleanAddress)
            ?.takeUnless(::isSelfReference)
            ?.let { return it }

        val profileTexts = (about as? JsonObject)
            ?.let { firstText(it, "profile_markdown", "profile_narrative", "about_me") }
            ?.let(::listOf)
            .orEmpty()
        val texts = profileTexts + payloads.flatMap { payload ->
            itemObjects(payload)
                .filterNot(::isScheduledTask)
                .flatMap { item ->
                    listOf("assertion", "summary", "text", "content", "verbatim_quote")
                        .mapNotNull { key -> firstText(item, key) }
                }
        }
        for (text in texts) {
            ADDRESS_PATTERNS.firstNotNullOfOrNull { pattern ->
                pattern.find(text)?.groupValues?.getOrNull(1)?.let(::cleanAddress)
            }
                ?.takeUnless(::isSelfReference)
                ?.let { return it }
        }
        return null
    }

    private fun cleanAddress(value: String): String? = value
        .trim()
        .trim('「', '」', '“', '”', '"', '\'', '：', ':')
        .takeIf { it.length in 1..24 && it.any(Char::isLetter) }

    private fun isSelfReference(value: String): Boolean =
        value.trim() in setOf("你", "您", "我", "自己", "用户")

    private fun parseEpisodes(payload: JsonElement?): List<MemoryEpisode> {
        return itemObjects(payload)
            .mapNotNull { item ->
                val summary = firstText(item, "summary", "story", "content", "text", "description")
                    ?: return@mapNotNull null
                MemoryEpisode(
                    id = firstText(item, "id", "episode_id", "uuid")
                        ?: stableEpisodeId(summary, item),
                    title = firstText(item, "title", "headline", "subject", "name")
                        ?.takeUnless { it == summary },
                    emotion = firstText(item, "emotion", "mood", "primary_emotion"),
                    summary = summary,
                    insight = firstText(
                        item,
                        "insight",
                        "reflection",
                        "growth",
                        "learning",
                        "lesson",
                        "memory",
                    )?.takeUnless { it == summary },
                    intensity = firstInt(item, "intensity", "intensity_score", "score", "level"),
                    occurredAt = firstTimestamp(item, "occurred_at", "created_at", "updated_at", "timestamp"),
                )
            }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<MemoryEpisode> { it.occurredAt ?: Long.MIN_VALUE })
            // Episode 接口在部分服务端版本会为一天产出多条候选；日记页按自然日只展示最终一篇。
            .distinctBy { episode ->
                episode.occurredAt?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                } ?: "undated-${episode.id}"
            }
            .take(EPISODE_MAX)
    }

    private fun parseDiaries(payload: JsonElement?): List<MemoryEpisode> {
        return itemObjects(payload)
            .mapNotNull { item ->
                val summary = firstText(item, "summary", "story", "content", "text")
                    ?: return@mapNotNull null
                val dayKey = firstText(item, "period_key", "date")
                MemoryEpisode(
                    id = firstText(item, "id", "episode_id", "uuid")
                        ?: stableEpisodeId(summary, item),
                    title = firstText(item, "title", "headline", "subject")
                        ?.takeUnless { it == summary },
                    emotion = firstText(item, "emotion", "mood", "primary_emotion"),
                    summary = summary,
                    insight = firstText(item, "growth", "insight", "reflection", "learning")
                        ?.takeUnless { it == summary },
                    intensity = firstInt(item, "intensity", "intensity_score", "score", "level"),
                    occurredAt = firstTimestamp(item, "occurred_at", "created_at", "updated_at", "timestamp")
                        ?: dayKey?.let(::parseLocalDayStart),
                )
            }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<MemoryEpisode> { it.occurredAt ?: Long.MIN_VALUE })
            .distinctBy { episode ->
                episode.occurredAt?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                } ?: "undated-${episode.id}"
            }
            .take(EPISODE_MAX)
    }

    private fun parseLocalDayStart(dayKey: String): Long? = runCatching {
        java.time.LocalDate.parse(dayKey)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private fun stableEpisodeId(summary: String, item: JsonObject): String =
        listOf(
            summary,
            firstText(item, "occurred_at", "created_at", "updated_at", "timestamp").orEmpty(),
            firstText(item, "emotion", "mood", "primary_emotion").orEmpty(),
        ).joinToString("|").hashCode().toUInt().toString(16)

    private fun buildArchives(
        entities: JsonElement?,
        atoms: JsonElement?,
        portrait: MemoryPortrait,
    ): List<MemoryArchiveItem> {
        val entityItems = itemObjects(entities).mapNotNull { item ->
            val title = firstText(
                item,
                "title",
                "name",
                "label",
                "summary",
                "text",
                "description",
            ) ?: return@mapNotNull null
            MemoryArchiveItem(
                kind = firstText(item, "entity_type", "kind", "type") ?: "entity",
                title = title,
                detail = secondaryText(item, primary = title),
                updatedAt = firstTimestamp(item, "updated_at", "created_at", "timestamp"),
            )
        }

        val atomItems = itemObjects(atoms).mapNotNull { item ->
            val title = firstText(
                item,
                "title",
                "summary",
                "text",
                "content",
                "value",
                "fact",
                "claim",
                "name",
                "description",
            )
                ?: return@mapNotNull null
            MemoryArchiveItem(
                kind = firstText(item, "candidate_type", "kind", "type"),
                title = title,
                detail = secondaryText(item, primary = title),
                updatedAt = firstTimestamp(item, "occurred_at", "created_at", "updated_at", "timestamp"),
            )
        }

        val fallbackItems = (portrait.truths + portrait.focus)
            .distinct()
            .map {
                MemoryArchiveItem(
                    kind = classifyProfileKind(it),
                    title = it,
                    detail = null,
                    updatedAt = null,
                )
            }

        val textFallback = extractTexts(entities)
            .map { MemoryArchiveItem(kind = "entity", title = it, detail = null, updatedAt = null) }

        return (atomItems + entityItems + textFallback + fallbackItems)
            .distinctBy { listOf(it.kind.orEmpty(), it.title, it.detail.orEmpty()).joinToString("|") }
            .take(ARCHIVE_MAX)
    }

    private fun secondaryText(item: JsonObject, primary: String): String? {
        return candidateTexts(item)
            .firstOrNull { it != primary }
    }

    private fun itemObjects(payload: JsonElement?): List<JsonObject> = when (payload) {
        null, JsonNull -> emptyList()
        is JsonArray -> payload.flatMap { element ->
            when (element) {
                is JsonObject -> listOf(element) + nestedObjectArrays(element, 1)
                is JsonArray -> itemObjects(element)
                else -> emptyList()
            }
        }
        is JsonObject -> {
            val arrays = listOf(
                payload["items"],
                payload["results"],
                payload["entries"],
                payload["episodes"],
                payload["atoms"],
                payload["entities"],
                payload["data"],
                payload["sections"],
                payload["blocks"],
                payload["pages"],
            )
            arrays.firstNotNullOfOrNull { (it as? JsonArray)?.takeIf { array -> array.isNotEmpty() } }
                ?.mapNotNull { it as? JsonObject }
                ?: (listOf(payload) + nestedObjectArrays(payload, 1))
        }

        else -> emptyList()
    }

    private fun extractTexts(payload: JsonElement?): List<String> = when (payload) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> payload.contentOrNull()
            ?.takeIf(::looksLikeReadableText)
            ?.let { splitReadableText(it) }
            ?: emptyList()
        is JsonArray -> payload.flatMap(::extractTexts)
        is JsonObject -> {
            val keyedTexts = listOf(
                payload["items"],
                payload["results"],
                payload["entries"],
                payload["lines"],
                payload["stories"],
                payload["bullets"],
                payload["episodes"],
                payload["data"],
                payload["sections"],
                payload["blocks"],
                payload["pages"],
                payload["content"],
                payload["body"],
                payload["page"],
            ).flatMap(::extractTexts)
            val directTexts = candidateTexts(payload).flatMap(::splitReadableText)
            val recursiveTexts = payload.values
                .take(RECURSIVE_VALUE_LIMIT)
                .flatMap(::extractTexts)
            (directTexts + keyedTexts + recursiveTexts)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
        }
    }

    private fun candidateTexts(item: JsonObject): List<String> = listOf(
        item["title"],
        item["summary"],
        item["text"],
        item["content"],
        item["body"],
        item["description"],
        item["value"],
        item["story"],
        item["focus"],
        item["fact"],
        item["claim"],
        item["label"],
        item["name"],
        item["subtitle"],
        item["headline"],
        item["message"],
        item["note"],
        item["markdown"],
    ).mapNotNull { (it as? JsonPrimitive)?.contentOrNull()?.trim()?.takeIf(::looksLikeReadableText) }

    private fun firstText(item: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (item[key] as? JsonPrimitive)?.contentOrNull()?.trim()?.takeIf(String::isNotBlank)
        }

    private fun firstInt(item: JsonObject, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            (item[key] as? JsonPrimitive)?.contentOrNull()?.toIntOrNull()
        }

    private fun firstTimestamp(item: JsonObject, vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            val raw = (item[key] as? JsonPrimitive)?.contentOrNull()?.trim()?.takeIf(String::isNotBlank)
                ?: return@firstNotNullOfOrNull null
            parseTimestamp(raw)
        }

    private fun parseTimestamp(raw: String): Long? {
        raw.toLongOrNull()?.let { number ->
            return if (number >= 1_000_000_000_000L) number else number * 1000
        }
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    private fun deriveHighlights(texts: List<String>, onlyFocus: Boolean): List<String> {
        val sentences = texts.flatMap(::splitReadableText)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        return if (onlyFocus) {
            sentences.filter(::looksLikeFocusSentence).ifEmpty { sentences }.take(PORTRAIT_LIST_MAX)
        } else {
            sentences.take(PORTRAIT_LIST_MAX)
        }
    }

    private fun splitReadableText(text: String): List<String> {
        return sanitizeTextCandidate(text)
            .replace("&#10;", "\n")
            .replace("&amp;#10;", "\n")
            .replace("\\n", "\n")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("\r", "\n")
            .split('\n', '。', '；', ';')
            .map { it.trim().trimStart('-', '•', '*', '.', '、') }
            .filter(::looksLikeReadableText)
    }

    private fun sanitizeTextCandidate(text: String): String {
        return text
            .replace(Regex("(?im)^\\s*(user|assistant|system|tool)\\s*$"), "")
            .replace(Regex("(?im)^\\s*(high|medium|low)\\s*$"), "")
            .replace(Regex("(?im)^\\s*decision\\s*$"), "")
            .replace(Regex("(?im)^\\s*\\d{4}-\\d{2}-\\d{2}([t ][0-9:.+-z]+)?\\s*$"), "")
    }

    private fun cleanProfileDisplayText(text: String): String {
        return sanitizeTextCandidate(text)
            .replace("&#10;", "\n")
            .replace("&amp;#10;", "\n")
            .replace("\\n", "\n")
            .split('\n')
            .map(String::trim)
            .filter(::looksLikeReadableText)
            .filter(::looksLikeProfileText)
            .distinct()
            .take(PORTRAIT_SUMMARY_MAX)
            .joinToString("\n\n")
    }

    private fun nestedObjectArrays(item: JsonObject, depth: Int): List<JsonObject> {
        if (depth > MAX_RECURSION_DEPTH) return emptyList()
        return item.values.flatMap { value ->
            when (value) {
                is JsonObject -> listOf(value) + nestedObjectArrays(value, depth + 1)
                is JsonArray -> value.flatMap { child ->
                    when (child) {
                        is JsonObject -> listOf(child) + nestedObjectArrays(child, depth + 1)
                        is JsonArray -> itemObjects(child)
                        else -> emptyList()
                    }
                }

                else -> emptyList()
            }
        }
    }

    private fun looksLikeReadableText(text: String): Boolean {
        val value = text.trim()
        if (value.length < 2) return false
        if (value.startsWith("http://") || value.startsWith("https://")) return false
        if (value.matches(Regex("^[A-Za-z0-9_-]{16,}$"))) return false
        if (value.matches(Regex("^\\d{4}-\\d{2}-\\d{2}([T ][0-9:.+-Z]+)?$"))) return false
        if (value.matches(Regex("^[0-9:.+-]+$"))) return false
        if (value.lowercase() in setOf("user", "assistant", "system", "tool", "high", "medium", "low", "decision")) return false
        return value.any { it.isLetter() || it.code > 127 }
    }

    private fun looksLikeProfileText(text: String): Boolean {
        val value = text.trim()
        return listOf(
            "Agent",
            "运行时",
            "OCR",
            "音视频",
            "转写链路",
            "会话日志",
            "多模态",
            "runtime",
            "audio event",
        ).none { marker -> value.contains(marker, ignoreCase = true) }
    }

    private fun looksLikeFocusSentence(text: String): Boolean =
        listOf("关注", "正在", "计划", "想要", "准备", "最近", "现在", "项目", "任务", "宝宝", "孩子")
            .any { marker -> text.contains(marker) }

    private fun classifyProfileKind(text: String): String = when {
        listOf("喜欢", "偏好", "习惯", "倾向", "常用", "主要用").any(text::contains) -> "preference"
        listOf("项目", "开发", "任务", "工作", "构建", "客户端", "安卓", "Octop").any(text::contains) -> "project"
        listOf("孩子", "宝宝", "家人", "老师", "身份", "名字", "称呼").any(text::contains) -> "person"
        else -> "profile"
    }

    private fun JsonPrimitive.contentOrNull(): String? =
        if (this == JsonNull) null else content

    private fun ApiResult<JsonElement>.valueOrNull(): JsonElement? = when (this) {
        is ApiResult.Ok -> value
        is ApiResult.Failure -> null
    }

    private companion object {
        val ADDRESS_PATTERNS = listOf(
            Regex("以[「“\\\"]([^」”\\\"]{1,24})[」”\\\"]称呼用户"),
            Regex("称(?:呼)?用户为[「“\\\"]?([^」”\\\"，,。；;]{1,24})"),
            Regex("(?:称呼我|叫我)(?:为|作|做|是|[：:])?[「“\\\"]?([^」”\\\"，,。；;\\s]{1,24})"),
            Regex("(?:惯用称呼|用户称呼)(?:是|为|[：:])[「“\\\"]?([^」”\\\"，,。；;]{1,24})"),
            Regex("(?:被助手|助手|大家|别人|同事)?称(?:呼)?为[「“\\\"]?([^」”\\\"，,。；;）)]{1,24})"),
        )
        const val PORTRAIT_SUMMARY_MAX = 4
        const val PORTRAIT_LIST_MAX = 4
        const val PORTRAIT_BRIEF_MAX = 180
        const val EPISODE_MAX = 60
        const val ARCHIVE_MAX = 8
        const val RECURSIVE_VALUE_LIMIT = 24
        const val MAX_RECURSION_DEPTH = 4
    }
}

data class ServerMemorySnapshot(
    val portrait: MemoryPortrait,
    val episodes: List<MemoryEpisode>,
    val archives: List<MemoryArchiveItem>,
)

data class MemoryPortrait(
    val preferredAddress: String?,
    val summary: String,
    val brief: String,
    val focus: List<String>,
    val truths: List<String>,
)

data class MemoryEpisode(
    val id: String,
    val title: String?,
    val emotion: String?,
    val summary: String,
    val insight: String?,
    val intensity: Int?,
    val occurredAt: Long?,
)

data class MemoryArchiveItem(
    val kind: String?,
    val title: String,
    val detail: String?,
    val updatedAt: Long?,
)
