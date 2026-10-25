package com.octopmobile.app.web

import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

/**
 * 只放行本实例的 http(s) 链接在壳内加载，其余一律交给系统浏览器。
 *
 * @param authority 本实例的 `host[:port]`，用于判定「内部链接」。
 * @param allowCleartext 用户是否显式允许明文 HTTP。
 */
class OctopWebViewClient(
    private val authority: String,
    private val allowCleartext: () -> Boolean,
    private val onExternalUrl: (Uri) -> Unit,
    private val onPageFinished: () -> Unit,
    private val onMainFrameError: (String?) -> Unit,
    private val onRenderProcessGone: () -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url ?: return false
        val scheme = url.scheme?.lowercase(Locale.ROOT)

        // mailto: / tel: / intent: / market: 等，交给系统处理
        if (scheme != "http" && scheme != "https") {
            onExternalUrl(url)
            return true
        }
        if (scheme == "http" && !allowCleartext()) {
            onExternalUrl(url)
            return true
        }
        if (!isInternal(url)) {
            onExternalUrl(url)
            return true
        }
        return false
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onPageFinished()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        // 只关心主框架失败，子资源失败不该覆盖整个页面
        if (request.isForMainFrame) {
            onMainFrameError(error.description?.toString())
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // 返回 true 表示已自行处理，避免整个应用被系统杀掉
        onRenderProcessGone()
        return true
    }

    private fun isInternal(url: Uri): Boolean = authorityOf(url) == authority

    private fun authorityOf(url: Uri): String = buildString {
        append(url.host)
        if (url.port > 0) append(':').append(url.port)
    }
}
