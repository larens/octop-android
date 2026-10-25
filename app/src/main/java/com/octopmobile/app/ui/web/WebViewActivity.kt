package com.octopmobile.app.ui.web

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.octopmobile.app.R
import com.octopmobile.app.data.AppPrefs
import com.octopmobile.app.databinding.ActivityMainBinding
import com.octopmobile.app.ui.MainActivity
import com.octopmobile.app.web.OctopWebChromeClient
import com.octopmobile.app.web.OctopWebViewClient
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * WebView 兜底通道（docs/spec.md §7 方案 C）。
 *
 * 原生界面缺少某个功能时从这里回到完整网页版：附件上传、下载、麦克风/摄像头授权、
 * 新窗口跳转都由这里兜住。原生侧只负责日常会话流。
 *
 * 与原生版共用同一份 DataStore 配置（[AppPrefs]），所以地址在两边始终一致。
 */
class WebViewActivity : AppCompatActivity(), OctopWebChromeClient.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs

    private var loadedBaseUrl: String? = null
    private var loadedAuthority: String = ""
    private var allowCleartext = false

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingResourceMap: List<Pair<String, String>> = emptyList()
    private var pendingDownload: (() -> Unit)? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        if (callback == null) return@registerForActivityResult
        callback.onReceiveValue(
            if (result.resultCode == RESULT_OK && result.data != null) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else {
                null
            },
        )
    }

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val request = pendingPermission
        val mapping = pendingResourceMap
        pendingPermission = null
        pendingResourceMap = emptyList()
        if (request == null) return@registerForActivityResult

        val grantedResources = mapping
            .filter { result[it.second] == true }
            .map { it.first }
            .toTypedArray()

        if (grantedResources.isEmpty()) request.deny() else request.grant(grantedResources)
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingDownload
        pendingDownload = null
        if (granted) action?.invoke() else toast(getString(R.string.err_storage_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        applyWindowInsets()
        setupWebView()
        setupUi()
        observePrefs()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 视图装配

    private fun applyWindowInsets() {
        // Android 15 起强制 edge-to-edge，必须自己处理系统栏与输入法遮挡
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // Octop 的语音播报走网页音频，默认策略会拦掉自动播放
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 跟随系统字体缩放会破坏 SPA 布局
            textZoom = 100
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webChromeClient = OctopWebChromeClient(this)
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun setupUi() {
        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }

        binding.retryButton.setOnClickListener {
            loadedBaseUrl?.let { loadHost(it) }
        }
        // 兜底页里没有地址可改，交回原生侧去改
        binding.openSettingsButton.setOnClickListener { openNative() }
        binding.settingsButton.setOnClickListener { openNative() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.webView.canGoBack()) {
                        binding.webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    private fun observePrefs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    prefs.allowCleartext.collect { allowed ->
                        if (allowCleartext != allowed) {
                            allowCleartext = allowed
                            // 明文开关变化后，http 实例需要重新加载才会生效
                            val base = loadedBaseUrl
                            if (base != null && base.startsWith("http://", ignoreCase = true)) {
                                loadedBaseUrl = null
                                loadHost(base)
                            }
                        }
                    }
                }
                launch {
                    prefs.host.collect { host ->
                        if (host.isNullOrBlank()) {
                            loadedBaseUrl = null
                            showError(
                                title = getString(R.string.err_not_configured_title),
                                detail = getString(R.string.err_not_configured),
                            )
                        } else if (host != loadedBaseUrl) {
                            loadHost(host)
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 加载

    private fun loadHost(baseUrl: String) {
        if (baseUrl.startsWith("http://", ignoreCase = true) && !allowCleartext) {
            loadedBaseUrl = baseUrl
            showError(
                title = getString(R.string.main_load_failed),
                detail = getString(R.string.err_cleartext_disabled),
            )
            return
        }

        loadedBaseUrl = baseUrl
        loadedAuthority = authorityOf(Uri.parse(baseUrl))

        binding.webView.webViewClient = OctopWebViewClient(
            authority = loadedAuthority,
            allowCleartext = { allowCleartext },
            onExternalUrl = ::openExternally,
            onPageFinished = { binding.swipeRefresh.isRefreshing = false },
            onMainFrameError = { description ->
                showError(getString(R.string.main_load_failed), description)
            },
            onRenderProcessGone = { recreate() },
        )

        showLoading()
        binding.webView.loadUrl(baseUrl)
    }

    private fun openNative() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun showLoading() {
        binding.loadingView.visibility = View.VISIBLE
        binding.errorView.visibility = View.GONE
    }

    private fun showError(title: String, detail: String?) {
        binding.loadingView.visibility = View.GONE
        binding.errorView.visibility = View.VISIBLE
        binding.errorTitle.text = title
        binding.errorDetail.text = detail.orEmpty()
        binding.errorDetail.visibility =
            if (detail.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.swipeRefresh.isRefreshing = false
    }

    // ---------------------------------------------------------------- 链接

    private fun openExternally(uri: Uri) {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        try {
            if (scheme == "http" || scheme == "https") {
                CustomTabsIntent.Builder().build().launchUrl(this, uri)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.err_no_app, uri.scheme.orEmpty()))
        }
    }

    override fun onNewWindowRequest(url: Uri) {
        val scheme = url.scheme?.lowercase(Locale.ROOT)
        val internal = authorityOf(url) == loadedAuthority
        val schemeAllowed = scheme == "https" || (scheme == "http" && allowCleartext)
        if (internal && schemeAllowed) {
            binding.webView.loadUrl(url.toString())
        } else {
            openExternally(url)
        }
    }

    // ---------------------------------------------------------------- 文件上传

    override fun onFileChooserRequest(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>,
    ) {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        try {
            fileChooserLauncher.launch(params.createIntent())
        } catch (e: ActivityNotFoundException) {
            pendingFileCallback = null
            callback.onReceiveValue(null)
            toast(getString(R.string.err_no_app, "file"))
        }
    }

    // ---------------------------------------------------------------- 网页权限

    override fun onWebPermissionRequest(resources: Array<String>, callback: PermissionRequest) {
        val mapping = resources.mapNotNull { resource ->
            androidPermissionFor(resource)?.let { resource to it }
        }
        if (mapping.isEmpty()) {
            callback.deny()
            return
        }

        val missing = mapping
            .map { it.second }
            .distinct()
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (missing.isEmpty()) {
            callback.grant(mapping.map { it.first }.toTypedArray())
            return
        }

        pendingPermission = callback
        pendingResourceMap = mapping
        webPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun androidPermissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        else -> null
    }

    // ---------------------------------------------------------------- 下载

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (!url.startsWith("http", ignoreCase = true)) {
            // blob: / data: 无法交给 DownloadManager
            runCatching { openExternally(Uri.parse(url)) }
            return
        }

        val action: () -> Unit = {
            runCatching {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    mimeType?.let { setMimeType(it) }
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                    )
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimeType),
                    )
                    CookieManager.getInstance().getCookie(url)?.let {
                        addRequestHeader("Cookie", it)
                    }
                    userAgent?.let { addRequestHeader("User-Agent", it) }
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                toast(getString(R.string.main_downloading))
            }.onFailure { toast(getString(R.string.err_download_failed)) }
        }

        // Android 9 及以下写公共下载目录需要存储权限
        val needsStorage = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

        if (needsStorage) {
            pendingDownload = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun authorityOf(uri: Uri): String = buildString {
        append(uri.host)
        if (uri.port > 0) append(':').append(uri.port)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
