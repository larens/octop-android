package com.octopmobile.app.ui.threads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.octopmobile.app.R
import com.octopmobile.app.data.db.ThreadTagEntity
import com.octopmobile.app.data.db.ThreadTagKind

/**
 * 会话标签编辑框（docs/spec.md §23.3）。
 *
 * 标签是**纯本地**的整理层（见 `TagRepository` 顶部）：上游没有 tag 接口，所以这里既不请求
 * 网络也不显示忙态 —— 加一条、删一条都立刻落到本机库，对话框里的列表跟着 Flow 变。
 *
 * 必须显式区分 `项目` 与 `主题` 两类，所以新增时要选一个 [ThreadTagKind]；已有标签按类显示。
 */
@Composable
fun TagEditorDialog(
    threadTitle: String,
    tags: List<ThreadTagEntity>,
    onAdd: (tag: String, kind: String) -> Unit,
    onRemove: (tag: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ThreadTagKind.PROJECT) }

    val submit = {
        if (text.isNotBlank()) {
            onAdd(text, kind)
            // 只清输入、不关对话框：整理标签通常是连着加几个
            text = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = threadTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (tags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tags_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    tags.forEach { tag ->
                        TagRow(tag = tag, onRemove = { onRemove(tag.tag) })
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.tags_add_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThreadTagKind.ALL.forEach { value ->
                        FilterChip(
                            selected = value == kind,
                            onClick = { kind = value },
                            label = { Text(tagKindLabel(value)) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = submit, enabled = text.isNotBlank()) {
                        Text(stringResource(R.string.tags_add))
                    }
                }
            }
        },
        // 没有「取消」：这里的每次增删都已经生效了，退出去只是关掉面板
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

@Composable
private fun TagRow(tag: ThreadTagEntity, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tag.tag,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = tagKindLabel(tag.kind),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.tags_remove),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 标签两类的显示名。 */
@Composable
fun tagKindLabel(kind: String): String = stringResource(
    if (kind == ThreadTagKind.TOPIC) R.string.tags_kind_topic else R.string.tags_kind_project,
)
