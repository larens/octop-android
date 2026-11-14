package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import androidx.compose.foundation.text.KeyboardOptions
import com.octopmobile.app.R

@Composable
internal fun ReadingSummaryPanel(
    summary: TodayReadingSummary,
    accent: Color,
    expanded: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            summary.metrics.chunked(3).forEach { rowMetrics ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    rowMetrics.forEach { metric ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(13.dp),
                            color = accent.copy(alpha = 0.09f),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            ) {
                                Text(
                                    text = metric.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent.copy(alpha = 0.82f),
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = metric.value,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    repeat(3 - rowMetrics.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (summary.books.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        text = stringResource(R.string.today_reading_books_title),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = accent,
                    )
                    Spacer(Modifier.height(5.dp))
                    summary.books.take(if (expanded) Int.MAX_VALUE else 2).forEach { book ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TodayMediaImage(
                                imageUrl = book.coverUrl,
                                fallbackText = book.title,
                                fallbackIcon = Icons.AutoMirrored.Filled.MenuBook,
                                accent = accent,
                                modifier = Modifier
                                    .width(46.dp)
                                    .height(62.dp),
                                shape = RoundedCornerShape(8.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "《${book.title}》",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = book.date,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = book.marks,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (expanded) {
            summary.remainingSections.forEach { section ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(13.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    section.heading?.let { heading ->
                        Text(
                            text = heading,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = accent,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = section.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TodayMediaImage(
    imageUrl: String?,
    fallbackText: String,
    fallbackIcon: ImageVector,
    accent: Color,
    modifier: Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.22f),
                        accent.copy(alpha = 0.07f),
                    ),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.10f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = fallbackText.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "·",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = accent.copy(alpha = 0.42f),
        )
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = accent.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(5.dp)
                .size(14.dp),
        )
        imageUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun TodayUsagePanel(
    details: TodayUsageDetails,
    accent: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            UsageMetricTile(
                label = stringResource(R.string.today_usage_cost),
                value = details.cost,
                accent = accent,
                emphasized = true,
                modifier = Modifier.weight(1f),
            )
            UsageMetricTile(
                label = stringResource(R.string.today_usage_savings),
                value = details.savings,
                accent = accent,
                emphasized = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            UsageMetricTile(
                label = stringResource(R.string.today_usage_total),
                value = details.total,
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            UsageMetricTile(
                label = stringResource(R.string.today_usage_cache),
                value = details.cacheHit,
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            UsageMetricTile(
                label = stringResource(R.string.today_usage_calls),
                value = details.calls,
                accent = accent,
                modifier = Modifier.weight(1f),
            )
        }
        val meta = listOfNotNull(
            details.sessions.takeIf { it != "—" }?.let {
                stringResource(R.string.today_usage_meta_sessions, it)
            },
            details.reasoning.takeIf { it != "—" }?.let {
                stringResource(R.string.today_usage_meta_reasoning, it)
            },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        details.breakdown?.let { breakdown ->
            UsageDetailBlock(
                label = stringResource(R.string.today_usage_breakdown),
                value = breakdown,
                accent = accent,
            )
        }
        details.models?.let { models ->
            UsageDetailBlock(
                label = stringResource(R.string.today_usage_models),
                value = models,
                accent = accent,
            )
        }
        details.composition?.let { composition ->
            UsageDetailBlock(
                label = stringResource(R.string.today_usage_sources),
                value = composition,
                accent = accent,
            )
        }
        details.comparison?.let { comparison ->
            Text(
                text = comparison,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}

@Composable
private fun UsageDetailBlock(
    label: String,
    value: String,
    accent: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = accent,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageMetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = accent.copy(alpha = 0.09f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accent.copy(alpha = 0.82f),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                style = if (emphasized) {
                    MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun TodayQuickReplyDialog(
    spec: TodayQuickReplySpec,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var amount by remember(spec) { mutableStateOf("") }
    var swipes by remember(spec) { mutableStateOf("") }
    var note by remember(spec) { mutableStateOf("") }
    var message by remember(spec) { mutableStateOf("") }
    val reconciliation = spec.kind == TodayQuickReplyKind.RECONCILIATION
    val canSubmit = if (reconciliation) {
        amount.trim().toDoubleOrNull() != null &&
            swipes.trim().toIntOrNull()?.let { it > 0 } == true
    } else {
        message.isNotBlank()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.today_task_reply_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = spec.request,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (reconciliation) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filterNumericInput() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.today_task_reply_amount)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = swipes,
                        onValueChange = { swipes = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.today_task_reply_swipes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.today_task_reply_note)) },
                        minLines = 2,
                        maxLines = 4,
                    )
                } else {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.today_task_reply_message)) },
                        minLines = 3,
                        maxLines = 7,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.today_task_reply_cancel))
                    }
                    Button(
                        onClick = {
                            val text = if (reconciliation) {
                                buildReconciliationReply(spec.title, amount, swipes, note)
                            } else {
                                buildGenericTaskReply(spec.title, message)
                            }
                            onSubmit(text)
                        },
                        enabled = canSubmit,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
                    ) {
                        Text(stringResource(R.string.today_task_reply_submit))
                    }
                }
            }
        }
    }
}

private fun String.filterNumericInput(): String {
    var seenDot = false
    return buildString {
        for (char in this@filterNumericInput) {
            when {
                char.isDigit() -> append(char)
                (char == '.' || char == '。') && !seenDot -> {
                    append('.')
                    seenDot = true
                }
            }
        }
    }.take(10)
}
