package com.octopmobile.app.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.octopmobile.app.R
import com.octopmobile.app.data.model.ArtifactPreviewKind
import com.octopmobile.app.data.model.ChatArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 已落入应用缓存的产物，用于全屏应用内预览。 */
data class ArtifactPreview(
    val artifact: ChatArtifact,
    val file: File,
)

@Composable
internal fun ArtifactPreviewDialog(
    preview: ArtifactPreview,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preview.artifact.filename,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = preview.artifact.formatLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                }
                HorizontalDivider()
                when (preview.artifact.previewKind) {
                    ArtifactPreviewKind.HTML -> HtmlArtifactPreview(preview.file)
                    ArtifactPreviewKind.MARKDOWN -> MarkdownArtifactPreview(preview.file)
                    ArtifactPreviewKind.EXTERNAL -> Unit
                }
            }
        }
    }
}

@Composable
private fun MarkdownArtifactPreview(file: File) {
    val state by produceState<MarkdownPreviewState>(
        initialValue = MarkdownPreviewState.Loading,
        key1 = file,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { MarkdownPreviewState.Ready(file.readText()) }
                .getOrElse { MarkdownPreviewState.Failed }
        }
    }

    when (val current = state) {
        MarkdownPreviewState.Loading -> PreviewLoading()
        MarkdownPreviewState.Failed -> PreviewError()
        is MarkdownPreviewState.Ready -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            ChatMarkdownText(
                markdown = current.text,
                contentColor = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun HtmlArtifactPreview(file: File) {
    val context = LocalContext.current
    var webView by remember(file) { mutableStateOf<WebView?>(null) }
    var loading by remember(file) { mutableStateOf(true) }
    var failed by remember(file) { mutableStateOf(false) }

    DisposableEffect(file) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.safeBrowsingEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (!request.isForMainFrame) return false
                            val url = request.url
                            if (url.scheme == "file") return true
                            openArtifactLink(context, url)
                            return true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                failed = true
                                loading = false
                            }
                        }
                    }
                    loadUrl(file.toUri().toString())
                }
            },
        )
        if (loading) PreviewLoading()
        if (failed) PreviewError()
    }
}

@Composable
private fun PreviewLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PreviewError() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.chat_artifact_preview_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private sealed interface MarkdownPreviewState {
    data object Loading : MarkdownPreviewState
    data object Failed : MarkdownPreviewState
    data class Ready(val text: String) : MarkdownPreviewState
}

private fun openArtifactLink(context: Context, uri: Uri) {
    try {
        if (uri.scheme == "http" || uri.scheme == "https") {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    } catch (_: ActivityNotFoundException) {
        // 链接不是产物本身，打开失败时不覆盖当前预览。
    }
}
