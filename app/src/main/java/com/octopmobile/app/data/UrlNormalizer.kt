package com.octopmobile.app.data

import java.net.URI

/**
 * 把用户随手输入的地址规整成可用于 WebView 的 base URL。
 *
 * Octop 必须部署在域名根路径（详见 README 的服务端配置），所以带路径的输入直接判错，
 * 而不是让它在后面以「白屏」的形式表现出来。
 */
object UrlNormalizer {

    sealed interface Outcome {

        /** [authority] 形如 `octop.example.com` 或 `octop.example.com:8443`，用于判断链接是否属于本实例。 */
        data class Valid(
            val baseUrl: String,
            val authority: String,
            val isCleartext: Boolean,
        ) : Outcome

        data class Invalid(val reason: Reason) : Outcome
    }

    enum class Reason {
        EMPTY,
        MALFORMED,
        UNSUPPORTED_SCHEME,
        HAS_PATH,
    }

    fun normalize(raw: String): Outcome {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Outcome.Invalid(Reason.EMPTY)

        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"

        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: return Outcome.Invalid(Reason.MALFORMED)

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Outcome.Invalid(Reason.UNSUPPORTED_SCHEME)
        }
        if (uri.host.isNullOrBlank() || uri.userInfo != null) {
            return Outcome.Invalid(Reason.MALFORMED)
        }
        if (!uri.path.orEmpty().trimEnd('/').isEmpty()) {
            return Outcome.Invalid(Reason.HAS_PATH)
        }

        // 显式写出的默认端口要省掉，否则同一个实例会出现两种 authority 写法
        val isDefaultPort =
            (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)

        val authority = buildString {
            append(uri.host)
            if (uri.port > 0 && !isDefaultPort) append(':').append(uri.port)
        }

        return Outcome.Valid(
            baseUrl = "$scheme://$authority",
            authority = authority,
            isCleartext = scheme == "http",
        )
    }
}
