package co.chatie.app.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.chatie.app.data.ChatRepository
import co.chatie.app.data.ConversationListItem
import co.chatie.app.ui.common.rememberQuery

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    repo: ChatRepository,
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val conversations = rememberQuery(repo) { repo.listConversations() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("chatie") },
                actions = {
                    IconButton(onClick = onOpenProjects) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "프로젝트")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "설정")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewConversation,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("새 대화") },
            )
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            EmptyState(Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            items(conversations, key = { it.conversation.id }) { item ->
                ConversationRow(item, onClick = { onOpenConversation(item.conversation.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ConversationRow(item: ConversationListItem, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.conversation.title.ifBlank { "새 대화" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            item.projectName?.let { name ->
                AssistChip(onClick = onClick, label = { Text(name, maxLines = 1) })
            }
        }
        item.lastMessage?.takeIf { it.isNotBlank() }?.let { preview ->
            Text(
                text = preview.replace('\n', ' '),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "${item.conversation.model} · 메시지 ${item.messageCount}개",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("대화가 없습니다", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "아래 '새 대화'로 시작하세요. 설정에서 BAZE API 키를 먼저 넣어야 합니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
