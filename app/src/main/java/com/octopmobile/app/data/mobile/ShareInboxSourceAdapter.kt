package com.octopmobile.app.data.mobile

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.octopmobile.app.data.db.MobileSourceType
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ShareInboxSourceAdapter(private val resolver: ContentResolver) {
    suspend fun collect(text: String?, uris: List<Uri>): MobileEventDraft = withContext(Dispatchers.IO) {
        var remaining = MAX_TOTAL_BYTES
        var skipped = 0
        val attachments = uris.take(MAX_ATTACHMENTS).mapNotNull { uri ->
            runCatching {
                val metadata = metadata(uri)
                val bytes = readAtMost(uri, remaining)
                if (bytes == null) {
                    skipped += 1
                    buildJsonObject {
                        put("name", metadata.first)
                        put("mime_type", metadata.second)
                        put("content_omitted", true)
                    }
                } else {
                    remaining -= bytes.size
                    buildJsonObject {
                        put("name", metadata.first)
                        put("mime_type", metadata.second)
                        put("content_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    }
                }
            }.getOrElse {
                skipped += 1
                buildJsonObject {
                    put("name", uri.lastPathSegment ?: "attachment")
                    put("mime_type", "application/octet-stream")
                    put("content_omitted", true)
                }
            }
        }
        skipped += (uris.size - MAX_ATTACHMENTS).coerceAtLeast(0)
        val payload = buildJsonObject {
            if (!text.isNullOrBlank()) put("text", text)
            putJsonArray("attachments") { attachments.forEach(::add) }
            put("skipped_attachment_count", skipped)
        }
        val now = System.currentTimeMillis()
        MobileEventDraft(
            id = "share-${UUID.randomUUID()}",
            sourceType = MobileSourceType.SHARE_INBOX,
            sourceRecordId = null,
            eventType = "share.inbox_item",
            occurredAt = now,
            title = "分享收集箱",
            summary = when {
                !text.isNullOrBlank() && uris.isNotEmpty() -> "分享文本与 ${uris.size} 个附件"
                !text.isNullOrBlank() -> "分享文本"
                else -> "${uris.size} 个附件${if (skipped > 0) "，$skipped 个仅保留元数据" else ""}"
            },
            payloadJson = payload.toString(),
            privacyClass = "private_user_content",
        )
    }

    private fun metadata(uri: Uri): Pair<String, String> {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "attachment"
        return name to (runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream")
    }

    private fun readAtMost(uri: Uri, remaining: Int): ByteArray? {
        if (remaining <= 0) return null
        return runCatching { resolver.openInputStream(uri) }.getOrNull()?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > remaining) return null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private companion object {
        const val MAX_ATTACHMENTS = 8
        const val MAX_TOTAL_BYTES = 10 * 1024 * 1024
    }
}
