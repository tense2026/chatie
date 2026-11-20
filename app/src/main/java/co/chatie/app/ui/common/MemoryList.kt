package co.chatie.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.chatie.app.context.ContextAssembler
import co.chatie.app.data.Memory

/**
 * Add, disable and delete memories at one scope.
 *
 * A disabled memory is kept but excluded from the request, which is the whole
 * point: you can see what exists and what is actually being sent, separately.
 */
@Composable
fun MemoryList(
    memories: List<Memory>,
    emptyHint: String,
    onAdd: (String) -> Unit,
    onToggle: (Memory, Boolean) -> Unit,
    onDelete: (Memory) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (memories.isEmpty()) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        memories.forEach { memory ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = memory.enabled,
                    onCheckedChange = { onToggle(memory, it) },
                )
                Text(
                    text = memory.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (memory.enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                )
                IconButton(onClick = { onDelete(memory) }) {
                    Icon(Icons.Default.Delete, contentDescription = "삭제")
                }
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = {
                if (it.length <= ContextAssembler.MAX_MEMORY_CHARS) draft = it
            },
            label = { Text("메모리 추가") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text("${draft.length} / ${ContextAssembler.MAX_MEMORY_CHARS}자")
            },
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = {
                    onAdd(draft.trim())
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text("추가") }
        }
    }
}
