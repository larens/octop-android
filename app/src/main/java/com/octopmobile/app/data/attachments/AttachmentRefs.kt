package com.octopmobile.app.data.attachments

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * 附件引用的归一化（docs/spec.md §13「服务端返回的附件 URL 一律由客户端归一化为稳定引用」）。
 *
 * 上游给的引用有好几种形态：历史 content block 里的 `url` / `workspace_path`，WS `attachment`
 * 事件里的 `url` / `preview_url` / `path`，workspace 内文件还会用 `workspace://` 自定义 scheme。
 * 这里统一收敛成**不含主机名的相对路径**：
 *
 * - 落库的是相对引用，所以换实例地址、换域名都不会让本地缓存对不上；
 * - 真正发请求时再用 [absolute] 补上实例地址。
 *
 * 拿不到可用引用时返回 null —— 调用方据此退回「只显示文件名」，而不是发一个注定 404 的请求。
 */
object AttachmentRefs {

    /** workspace 内文件的下载端点（上游 `infra/gateway/media/backend_files.py:workspace_download_url`）。 */
    private const val WORKSPACE_DOWNLOAD = "/api/agents/%s/workspace/download?path=%s"

    private const val WORKSPACE_SCHEME = "workspace://"

    fun canonical(agentId: String, url: String?, workspacePath: String? = null): String? {
        val raw = url?.takeIf { it.isNotBlank() }
        return when {
            raw == null -> workspacePath?.takeIf { it.isNotBlank() }?.let { download(agentId, it) }
            raw.startsWith(HTTP) || raw.startsWith(HTTPS) -> raw
            raw.startsWith(WORKSPACE_SCHEME) -> download(agentId, raw.removePrefix(WORKSPACE_SCHEME))
            else -> raw
        }
    }

    /** 相对引用 → 绝对地址。已经是绝对地址的原样返回；两者都不是时返回 null。 */
    fun absolute(baseUrl: String, ref: String): String? = when {
        ref.startsWith(HTTP) || ref.startsWith(HTTPS) -> ref
        ref.startsWith("/") -> baseUrl.trimEnd('/') + ref
        else -> null
    }

    /**
     * 引用的稳定 id（落盘文件名、`attachments` 表主键）。
     *
     * 用内容哈希而不是随机数：同一张图被历史与 WS 各报一次时会得到同一个 id，两条记录自动合并，
     * 磁盘上也不会存两份。
     */
    fun idOf(threadId: String, ref: String): String =
        MessageDigest.getInstance("MD5")
            .digest("$threadId|$ref".toByteArray())
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }

    /** 图片在气泡里内联渲染，其余只显示文件名。 */
    fun isImage(mime: String?): Boolean = mime?.startsWith("image/") == true

    /**
     * 内联帧（只有 base64、没有任何 URL）的合成引用。
     *
     * 用**内容哈希**当键：同一个文件被重复下发时得到同一个引用，磁盘上不会存两份。
     * 这种引用没有对应的远端地址，因此只能读缓存、不能再下载 —— [absolute] 会返回 null。
     */
    fun inlineRef(bytes: ByteArray): String =
        INLINE_PREFIX + hash(bytes)

    /**
     * 解 WS `attachment` 事件里的 base64 载荷。
     *
     * 上游给的是裸 base64，但同一段代码库别处也用过 `data:<mime>;base64,<payload>` 形态，
     * 所以有逗号时按 data URL 截断；解不出来返回 null（调用方据此当作没带载荷）。
     */
    fun decodeBase64(data: String): ByteArray? {
        val payload = data.substringAfter(',', data)
        return runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull()
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }

    private fun download(agentId: String, path: String): String =
        WORKSPACE_DOWNLOAD.format(agentId, URLEncoder.encode(path, "UTF-8"))

    private const val INLINE_PREFIX = "inline:"
    private const val HTTP = "http://"
    private const val HTTPS = "https://"
}
