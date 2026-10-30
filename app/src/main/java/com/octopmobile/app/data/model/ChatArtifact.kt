package com.octopmobile.app.data.model

import com.octopmobile.app.data.attachments.AttachmentRefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 服务端历史响应里一条可下载的会话产物。 */
data class ChatArtifact(
    val agentId: String,
    val path: String,
    val filename: String,
    val mimeType: String,
) {
    val key: String = "$agentId\u0000$path"

    val extension: String
        get() = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)

    val formatLabel: String
        get() = extension.uppercase(Locale.ROOT).ifBlank { "FILE" }

    val isHtml: Boolean
        get() = extension == "html" || extension == "htm"

    val isMarkdown: Boolean
        get() = extension == "md" || extension == "markdown"

    val previewKind: ArtifactPreviewKind
        get() = when {
            isHtml -> ArtifactPreviewKind.HTML
            isMarkdown -> ArtifactPreviewKind.MARKDOWN
            else -> ArtifactPreviewKind.EXTERNAL
        }

    /** 下载时仍走带鉴权的 workspace 端点；记录归属 agent 以支持专家产物。 */
    val downloadRef: String?
        get() = AttachmentRefs.canonical(agentId, url = null, workspacePath = path)
}

enum class ArtifactPreviewKind {
    HTML,
    MARKDOWN,
    EXTERNAL,
}

/** 把新旧两种历史响应字段收敛成去重、可排序的 UI 模型。 */
fun chatArtifacts(
    defaultAgentId: String,
    references: List<ArtifactReference>,
): List<ChatArtifact> {
    val seen = mutableSetOf<String>()
    return references.mapNotNull { reference ->
        val path = reference.path.trim()
        if (path.isBlank()) return@mapNotNull null
        val agentId = reference.agentId?.takeIf { it.isNotBlank() } ?: defaultAgentId
        val filename = artifactFilename(path)
        val artifact = ChatArtifact(
            agentId = agentId,
            path = path,
            filename = filename,
            mimeType = artifactMimeType(filename),
        )
        artifact.takeIf { seen.add(it.key) }
    }
}

data class ArtifactReference(
    val path: String,
    val agentId: String? = null,
)

internal fun artifactMimeType(filename: String): String =
    when (filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "htm", "html" -> "text/html"
        "md", "markdown" -> "text/markdown"
        "txt", "log" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

internal fun artifactFilename(path: String): String =
    path.trim()
        .replace('\\', '/')
        .trimEnd('/')
        .substringAfterLast('/')
        .ifBlank { "artifact" }

/**
 * `DownloadManager` 会直接使用文件名，必须把路径分隔符和 Windows 保留字符清掉；
 * 加时间戳避免同名报告反复生成时互相覆盖。
 */
fun artifactDownloadName(filename: String, timestamp: Long): String {
    val base = artifactFilename(filename)
    val safe = base
        .replace(Regex("""[\u0000-\u001f<>:"/\\|?*]"""), "_")
        .trim()
        .trimStart('.')
        .ifBlank { "artifact" }
    val stem = safe.substringBeforeLast('.', safe)
    val extension = safe.substringAfterLast('.', "")
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestamp))
    return if (extension.isBlank()) {
        "$stem-$stamp"
    } else {
        "$stem-$stamp.$extension"
    }
}
