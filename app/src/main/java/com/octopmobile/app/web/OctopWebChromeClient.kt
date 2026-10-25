package com.octopmobile.app.web

import android.net.Uri
import android.os.Message
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 补上 WebView 默认不会做的三件事：新窗口、文件选择、网页权限申请。
 * 每一项都对应一个用户可感知的功能，缺了就会出现「点了没反应」。
 */
class OctopWebChromeClient(private val host: Host) : WebChromeClient() {

    interface Host {

        /** `target=_blank` / `window.open` 的新窗口请求，交回主 WebView 加载。 */
        fun onNewWindowRequest(url: Uri)

        /** 网页的文件选择请求（附件上传）。 */
        fun onFileChooserRequest(
            params: FileChooserParams,
            callback: ValueCallback<Array<Uri>>,
        )

        /** 网页申请的 Android 权限（麦克风 / 摄像头）。 */
        fun onWebPermissionRequest(resources: Array<String>, callback: PermissionRequest)
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        // WebView 必须先把一个 WebView 实例塞回 transport 才会触发导航，
        // 这里用一个只在回调里活着的临时实例把 URL 截出来。
        val probe = WebView(view.context)
        probe.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                probeView: WebView,
                request: WebResourceRequest,
            ): Boolean {
                host.onNewWindowRequest(request.url)
                probeView.destroy()
                return true
            }
        }
        (resultMsg.obj as WebView.WebViewTransport).webView = probe
        resultMsg.sendToTarget()
        return true
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        host.onFileChooserRequest(fileChooserParams, filePathCallback)
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        host.onWebPermissionRequest(request.resources, request)
    }
}
