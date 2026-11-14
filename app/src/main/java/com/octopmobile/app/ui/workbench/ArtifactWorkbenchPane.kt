package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octopmobile.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class ArtifactFilter { ALL, WEB, DOC, SHEET, DECK, OTHER }

/** Cross-conversation index of files the agent has produced. */
@Composable
internal fun ArtifactWorkbenchPane(
    items: List<ArtifactWorkbenchItem>,
    loading: Boolean,
    busyKey: String?,
    shareBusyKey: String?,
    onPreview: (ArtifactWorkbenchItem) -> Unit,
    onDownload: (ArtifactWorkbenchItem) -> Unit,
    onOpenThread: (ArtifactWorkbenchItem) -> Unit,
    onShare: (ArtifactWorkbenchItem) -> Unit,
    onContinue: (ArtifactWorkbenchItem) -> Unit,
) {
    var filterName by rememberSaveable { mutableStateOf(ArtifactFilter.ALL.name) }
    val filter = ArtifactFilter.entries.firstOrNull { it.name == filterName } ?: ArtifactFilter.ALL
    val visible = items.filter { filter.matches(it.artifact.extension) }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ArtifactFilter.entries) { option ->
                FilterChip(
                    selected = option == filter,
                    onClick = { filterName = option.name },
                    label = { Text(stringResource(option.labelRes())) },
                )
            }
        }
        when {
            loading && items.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            visible.isEmpty() -> Box(Modifier.padding(16.dp)) {
                EmptyCard(
                    title = stringResource(R.string.artifacts_empty_title),
                    body = stringResource(R.string.artifacts_empty_body),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.artifact.key }) { item ->
                    ArtifactRow(
                        item = item,
                        busy = item.artifact.key == busyKey,
                        shareBusy = item.artifact.key == shareBusyKey,
                        onPreview = { onPreview(item) },
                        onDownload = { onDownload(item) },
                        onOpenThread = { onOpenThread(item) },
                        onShare = { onShare(item) },
                        onContinue = { onContinue(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtifactRow(
    item: ArtifactWorkbenchItem,
    busy: Boolean,
    shareBusy: Boolean,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onOpenThread: () -> Unit,
    onShare: () -> Unit,
    onContinue: () -> Unit,
) {
    var menuExpanded by rememberSaveable(item.artifact.key) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(13.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.artifact.formatLabel.take(4),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.artifact.filename,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.artifacts_from_thread,
                            item.threadTitle,
                            formatArtifactTime(item.updatedAt),
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.artifacts_open_thread),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable(onClick = onOpenThread),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onPreview) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = stringResource(R.string.chat_artifact_preview),
                    )
                }
                IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = stringResource(R.string.chat_artifact_download),
                    )
                }
                if (shareBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.artifacts_more_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            if (item.artifact.isHtml) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.artifacts_share)) },
                                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        onShare()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.artifacts_continue)) },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onContinue()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun ArtifactFilter.matches(extension: String): Boolean = when (this) {
    ArtifactFilter.ALL -> true
    ArtifactFilter.WEB -> extension == "html" || extension == "htm"
    ArtifactFilter.DOC -> extension in setOf("md", "markdown", "txt", "pdf", "doc", "docx")
    ArtifactFilter.SHEET -> extension in setOf("csv", "xls", "xlsx")
    ArtifactFilter.DECK -> extension in setOf("ppt", "pptx", "key")
    ArtifactFilter.OTHER -> extension !in setOf(
        "html", "htm", "md", "markdown", "txt", "pdf", "doc", "docx",
        "csv", "xls", "xlsx", "ppt", "pptx", "key",
    )
}

private fun ArtifactFilter.labelRes(): Int = when (this) {
    ArtifactFilter.ALL -> R.string.artifacts_filter_all
    ArtifactFilter.WEB -> R.string.artifacts_filter_web
    ArtifactFilter.DOC -> R.string.artifacts_filter_doc
    ArtifactFilter.SHEET -> R.string.artifacts_filter_sheet
    ArtifactFilter.DECK -> R.string.artifacts_filter_deck
    ArtifactFilter.OTHER -> R.string.artifacts_filter_other
}

private fun formatArtifactTime(millis: Long): String {
    if (millis <= 0L) return ""
    return DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
}
