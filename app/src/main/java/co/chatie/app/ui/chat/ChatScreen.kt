package co.chatie.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.chatie.app.data.Message
import co.chatie.app.ui.common.MarkdownText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenInspector: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenChatSettings: () -> Unit,
) {
    val conversation = vm.conversation
    val streaming = vm.streaming
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = conversation?.title?.ifBlank { "새 대화" } ?: "대화",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val subtitle = listOfNotNull(
                            vm.contextSummary?.projectName,
                            conversation?.model,
                        ).joinToString(" · ")
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenModelPicker) {
                        Icon(Icons.Default.Tune, contentDescription = "모델 선택")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "더보기")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("보내는 컨텍스트 보기") },
                            onClick = { menuOpen = false; onOpenInspector() },
                        )
                        DropdownMenuItem(
                            text = { Text("이 대화 설정") },
                            onClick = { menuOpen = false; onOpenChatSettings() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            vm.errorMessage?.let { message ->
                ErrorBanner(
                    message = message,
                    onRetry = { vm.dismissError(); vm.retryLast() },
                    onDismiss = vm::dismissError,
                )
            }

            // reverseLayout keeps the newest turn pinned to the bottom with no
            // scroll-to-bottom animation on every token.
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (streaming != null) {
                    item(key = "streaming") {
                        StreamingBubble(streaming)
                    }
                }
                items(
                    items = vm.messages.asReversed(),
                    key = Message::id,
                    contentType = { it.role },
                ) { message ->
                    if (streaming?.messageId != message.id) MessageBubble(message)
                }
            }

            vm.contextSummary?.let { summary ->
                ContextChip(summary, onClick = onOpenInspector)
            }

            Composer(
                isStreaming = vm.isStreaming,
                onSend = vm::send,
                onStop = vm::stop,
            )
        }
    }
}

/**
 * The in-flight answer.
 *
 * Deliberately plain [Text] rather than [MarkdownText]: parsing partial
 * markdown on every flush is the single biggest cost in a streaming chat UI,
 * and half-written syntax renders badly anyway. The finished message is
 * re-rendered as markdown the moment the stream ends.
 */
@Composable
private fun StreamingBubble(streaming: ChatViewModel.Streaming) {
    Column(Modifier.fillMaxWidth()) {
        if (streaming.waitingForFirstToken) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = "생각하는 중…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            return@Column
        }

        if (streaming.reasoning.isNotEmpty() && streaming.text.isEmpty()) {
            Text(
                text = streaming.reasoning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (streaming.text.isNotEmpty()) {
            Text(streaming.text)
        }
    }
}

/** What is actually being sent, one line, tappable for the full breakdown. */
@Composable
private fun ContextChip(summary: ChatViewModel.ContextSummary, onClick: () -> Unit) {
    val layers = if (summary.includedLayers.isEmpty()) {
        "레이어 없음"
    } else {
        summary.includedLayers.joinToString(" + ")
    }
    val omitted = if (summary.historyOmitted > 0) " · 이전 ${summary.historyOmitted}개 생략" else ""

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$layers · 메시지 ${summary.historySent}개$omitted",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClick) { Text("보기", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text("다시 시도") }
        TextButton(onClick = onDismiss) { Text("닫기") }
    }
}

@Composable
private fun Composer(
    isStreaming: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("메시지를 입력하세요") },
            maxLines = 6,
            shape = RoundedCornerShape(22.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(),
        )

        Box(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
            if (isStreaming) {
                FilledIconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "중지")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        val text = draft
                        draft = ""
                        onSend(text)
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "보내기")
                }
            }
        }
    }
}
