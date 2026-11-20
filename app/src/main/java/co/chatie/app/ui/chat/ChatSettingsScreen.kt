package co.chatie.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.chatie.app.context.ContextAssembler
import co.chatie.app.data.ChatRepository
import co.chatie.app.data.MemoryScope
import co.chatie.app.ui.common.MemoryList
import co.chatie.app.ui.common.rememberQuery

/**
 * Per-conversation context switches.
 *
 * Every layer the assembler can apply has its control here or on the project
 * screen, and the global-memory switch is explicitly three-state so "inherit"
 * is a visible choice rather than a hidden default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    repo: ChatRepository,
    assembler: ContextAssembler,
    conversationId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val conversation = rememberQuery(repo, conversationId) { repo.getConversation(conversationId) }
    val projects = rememberQuery(repo) { repo.listProjects() }
    val chatMemories = rememberQuery(repo, conversationId) {
        repo.listMemories(MemoryScope.CONVERSATION, conversationId)
    }

    if (conversation == null) {
        onBack()
        return
    }

    val project = conversation.projectId?.let { id -> projects.firstOrNull { it.id == id } }
    val globalSwitch = assembler.resolveGlobalMemory(conversation, project)

    var overrideDraft by remember(conversation.id) {
        mutableStateOf(conversation.systemOverride ?: "")
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val overrideDirty = overrideDraft != (conversation.systemOverride ?: "")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("이 대화 설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionTitle("프로젝트")
            Text(
                text = "대화는 프로젝트 0개 또는 1개에 속합니다. 옮기면 적용되는 지시문·메모리가 즉시 바뀝니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = conversation.projectId == null,
                    onClick = { repo.updateConversation(conversationId, clearProject = true) },
                    label = { Text("없음") },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                projects.forEach { p ->
                    FilterChip(
                        selected = conversation.projectId == p.id,
                        onClick = { repo.updateConversation(conversationId, projectId = p.id) },
                        label = { Text(p.name) },
                    )
                }
            }

            HorizontalDivider()

            SectionTitle("적용할 레이어")

            ToggleRow(
                title = "프로젝트 지시문 사용",
                subtitle = project?.let {
                    if (it.instructions.isBlank()) "'${it.name}'의 지시문이 비어 있음" else "'${it.name}'에서 가져옴"
                } ?: "프로젝트에 속하지 않아 적용할 수 없음",
                checked = conversation.useProjectInstructions,
                enabled = project != null,
                onCheckedChange = {
                    repo.updateConversation(conversationId, useProjectInstructions = it)
                },
            )

            ToggleRow(
                title = "프로젝트 메모리 사용",
                subtitle = project?.let { "'${it.name}'의 메모리만 들어갑니다" }
                    ?: "프로젝트에 속하지 않아 적용할 수 없음",
                checked = conversation.useProjectMemory,
                enabled = project != null,
                onCheckedChange = {
                    repo.updateConversation(conversationId, useProjectMemory = it)
                },
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TriStateCheckbox(
                        state = when (conversation.useGlobalMemory) {
                            true -> ToggleableState.On
                            false -> ToggleableState.Off
                            null -> ToggleableState.Indeterminate
                        },
                        onClick = {
                            // cycle: inherit -> on -> off -> inherit
                            when (conversation.useGlobalMemory) {
                                null -> repo.updateConversation(conversationId, useGlobalMemory = true)
                                true -> repo.updateConversation(conversationId, useGlobalMemory = false)
                                false -> repo.updateConversation(conversationId, inheritGlobalMemory = true)
                            }
                        },
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text("전역 메모리 사용", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = when (conversation.useGlobalMemory) {
                                true -> "이 대화에서 켬"
                                false -> "이 대화에서 끔"
                                null -> "상속: ${globalSwitch.reason}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AssistChip(
                    onClick = {
                        when (conversation.useGlobalMemory) {
                            null -> repo.updateConversation(conversationId, useGlobalMemory = true)
                            true -> repo.updateConversation(conversationId, useGlobalMemory = false)
                            false -> repo.updateConversation(conversationId, inheritGlobalMemory = true)
                        }
                    },
                    label = {
                        Text(
                            when (conversation.useGlobalMemory) {
                                null -> "켜기"
                                true -> "끄기"
                                false -> "상속으로 되돌리기"
                            },
                        )
                    },
                )
            }

            HorizontalDivider()

            SectionTitle("대화 메모리")
            Text(
                text = "이 대화에만 붙는 메모리입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MemoryList(
                memories = chatMemories,
                emptyHint = "아직 이 대화의 메모리가 없습니다.",
                onAdd = { repo.createMemory(MemoryScope.CONVERSATION, it, conversationId) },
                onToggle = { memory, enabled -> repo.updateMemory(memory.id, enabled = enabled) },
                onDelete = { repo.deleteMemory(it.id) },
            )

            HorizontalDivider()

            SectionTitle("전용 시스템 프롬프트")
            Text(
                text = "입력하면 위의 모든 레이어를 대체합니다. 비워 두면 레이어가 정상 적용됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = overrideDraft,
                onValueChange = {
                    if (it.length <= ContextAssembler.MAX_INSTRUCTIONS_CHARS) overrideDraft = it
                },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { overrideDraft = conversation.systemOverride ?: "" },
                    enabled = overrideDirty,
                ) { Text("되돌리기") }
                TextButton(
                    onClick = {
                        if (overrideDraft.isBlank()) {
                            repo.updateConversation(conversationId, clearSystemOverride = true)
                        } else {
                            repo.updateConversation(conversationId, systemOverride = overrideDraft)
                        }
                    },
                    enabled = overrideDirty,
                ) { Text("저장") }
            }

            HorizontalDivider()

            TextButton(onClick = { confirmDelete = true }) {
                Text("대화 삭제", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("대화를 삭제할까요?") },
            text = { Text("메시지와 이 대화의 메모리가 모두 사라집니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        repo.deleteConversation(conversationId)
                        onDeleted()
                    },
                ) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}
