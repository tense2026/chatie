package co.chatie.app.ui.projects

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.chatie.app.context.ContextAssembler
import co.chatie.app.data.ChatRepository
import co.chatie.app.data.MemoryScope
import co.chatie.app.ui.common.MemoryList
import co.chatie.app.ui.common.rememberQuery

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    repo: ChatRepository,
    projectId: String,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onNewConversationInProject: (String) -> Unit,
) {
    val project = rememberQuery(repo, projectId) { repo.getProject(projectId) }
    val conversations = rememberQuery(repo, projectId) {
        repo.listConversations(projectId = projectId)
    }
    val memories = rememberQuery(repo, projectId) {
        repo.listMemories(MemoryScope.PROJECT, projectId)
    }

    if (project == null) {
        onBack()
        return
    }

    // The editor keeps its own draft so typing never writes to the database;
    // the explicit save is what makes a change take effect.
    var instructions by remember(project.id) { mutableStateOf(project.instructions) }
    var confirmDelete by remember { mutableStateOf(false) }
    val instructionsDirty = instructions != project.instructions

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionTitle("지시문")
            Text(
                text = "이 프로젝트의 모든 대화에 자동으로 들어갑니다. 다른 프로젝트에는 영향이 없습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = instructions,
                onValueChange = {
                    if (it.length <= ContextAssembler.MAX_INSTRUCTIONS_CHARS) instructions = it
                },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("${instructions.length} / ${ContextAssembler.MAX_INSTRUCTIONS_CHARS}자")
                },
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { instructions = project.instructions },
                    enabled = instructionsDirty,
                ) { Text("되돌리기") }
                TextButton(
                    onClick = { repo.updateProject(projectId, instructions = instructions) },
                    enabled = instructionsDirty,
                ) { Text("저장") }
            }

            HorizontalDivider()

            SectionTitle("메모리")
            Text(
                text = "이 프로젝트의 대화에만 들어가는 맥락입니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MemoryList(
                memories = memories,
                emptyHint = "아직 이 프로젝트의 메모리가 없습니다.",
                onAdd = { repo.createMemory(MemoryScope.PROJECT, it, projectId) },
                onToggle = { memory, enabled -> repo.updateMemory(memory.id, enabled = enabled) },
                onDelete = { repo.deleteMemory(it.id) },
            )

            HorizontalDivider()

            SectionTitle("격리 설정")
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("전역 메모리도 사용", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "끄면 이 프로젝트의 대화는 프로젝트 안의 지시문·메모리만 봅니다. " +
                            "기본값은 끔입니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = project.useGlobalMemory,
                    onCheckedChange = { repo.updateProject(projectId, useGlobalMemory = it) },
                )
            }

            HorizontalDivider()

            SectionTitle("대화 ${conversations.size}개")
            OutlinedButton(onClick = { onNewConversationInProject(projectId) }) {
                Text("이 프로젝트에서 새 대화")
            }
            conversations.forEach { item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenConversation(item.conversation.id) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        text = item.conversation.title.ifBlank { "새 대화" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.conversation.model} · 메시지 ${item.messageCount}개",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            TextButton(onClick = { confirmDelete = true }) {
                Text("프로젝트 삭제", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("프로젝트를 삭제할까요?") },
            text = {
                Text(
                    "지시문과 메모리는 사라집니다. 대화 ${conversations.size}개는 삭제되지 않고 " +
                        "'프로젝트 없음'으로 남습니다.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        repo.deleteProject(projectId)
                        onBack()
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
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}
