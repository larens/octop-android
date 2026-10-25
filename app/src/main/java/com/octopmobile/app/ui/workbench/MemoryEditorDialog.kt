package com.octopmobile.app.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.octopmobile.app.R
import com.octopmobile.app.data.db.MemoryKind

/**
 * 一条记忆的编辑草稿。
 *
 * [id] 为空即「新建」；[sourceThreadId] 非空表示这条是从某个会话里存下来的
 * （来源只在这里传一次，之后编辑不再改它，见 [MemoryViewModel.save]）。
 */
data class MemoryDraft(
    val id: String? = null,
    val kind: String = MemoryKind.PERSON,
    val title: String = "",
    val summary: String = "",
    val sourceThreadId: String? = null,
)

/**
 * 记忆编辑框。记忆页与「会话 → 存为记忆」共用同一个（docs/spec.md §23.3）。
 *
 * 以 [MemoryDraft] 为 key 重置内部输入：换一条记忆编辑时输入框要跟着换，而不是留着上一条。
 */
@Composable
fun MemoryEditorDialog(
    initial: MemoryDraft,
    onDismiss: () -> Unit,
    onConfirm: (MemoryDraft) -> Unit,
) {
    var kind by remember(initial) { mutableStateOf(initial.kind) }
    var title by remember(initial) { mutableStateOf(initial.title) }
    var summary by remember(initial) { mutableStateOf(initial.summary) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial.id == null) R.string.memory_new else R.string.memory_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoryKind.ALL.forEach { value ->
                        FilterChip(
                            selected = value == kind,
                            onClick = { kind = value },
                            label = { Text(memoryKindLabel(value)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.memory_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text(stringResource(R.string.memory_summary_label)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            // 没有标题的记忆在列表上认不出来，所以这里就拦住（仓储层也会再挡一次）
            TextButton(
                onClick = { onConfirm(initial.copy(kind = kind, title = title, summary = summary)) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 记忆四类的显示名。 */
@Composable
fun memoryKindLabel(kind: String): String = stringResource(
    when (kind) {
        MemoryKind.PROJECT -> R.string.memory_kind_project
        MemoryKind.PREFERENCE -> R.string.memory_kind_preference
        MemoryKind.COMMITMENT -> R.string.memory_kind_commitment
        else -> R.string.memory_kind_person
    },
)
