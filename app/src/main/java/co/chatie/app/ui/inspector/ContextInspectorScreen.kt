package co.chatie.app.ui.inspector

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.chatie.app.context.ContextAssembler
import co.chatie.app.data.ChatRepository
import co.chatie.app.ui.common.rememberQuery
import co.chatie.app.ui.theme.CodeTextStyle
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * The answer to "why did it say that?".
 *
 * Shows every layer — included or not — with the reason it was or was not
 * sent, the exact text it contributed, and the final payload. If a layer is
 * not on this screen, it was not in the request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextInspectorScreen(
    repo: ChatRepository,
    assembler: ContextAssembler,
    conversationId: String,
    onBack: () -> Unit,
) {
    val assembled = rememberQuery(repo, conversationId) { assembler.assemble(conversationId) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    if (assembled == null) {
        onBack()
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("보내는 컨텍스트") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val dump = buildDump(assembled)
                            scope.launch {
                                clipboard.setClipEntry(
                                    androidx.compose.ui.platform.ClipEntry(
                                        android.content.ClipData.newPlainText("context", dump),
                                    ),
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "전체 복사")
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Summary(assembled)

            HorizontalDivider()

            Text(
                text = "레이어",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "위에서 아래 순서로 시스템 프롬프트에 들어갑니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            assembled.layers.forEach { layer -> LayerCard(layer) }

            HorizontalDivider()

            Text(
                text = "대화 기록",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = with(assembled.history) {
                    buildString {
                        append("메시지 ${sent.size}개 · ${chars}자")
                        append(" (예산 ${budget}자)")
                        if (omitted > 0) append(" · 오래된 ${omitted}개는 예산을 넘어 생략")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            Text(
                text = "최종 시스템 프롬프트",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            MonoBlock(assembled.system ?: "(없음 — 시스템 메시지를 보내지 않습니다)")
        }
    }
}

@Composable
private fun Summary(assembled: ContextAssembler.Assembled) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = assembled.conversation.title.ifBlank { "새 대화" },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = buildString {
                append("모델 ${assembled.conversation.model}")
                append(" · ")
                append(assembled.project?.let { "프로젝트 ${it.name}" } ?: "프로젝트 없음")
                append(" · 총 ${assembled.totalChars}자")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LayerCard(layer: ContextAssembler.Layer) {
    var expanded by remember(layer.id) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = layer.text.isNotEmpty()) { expanded = !expanded }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(layer.included)
            Text(
                text = layer.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            Text(
                text = if (layer.included) "${layer.chars}자" else "미포함",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = layer.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = when (layer.controlledBy) {
                ContextAssembler.ControlledBy.SETTINGS -> "설정에서 변경"
                ContextAssembler.ControlledBy.PROJECT -> "프로젝트 화면에서 변경"
                ContextAssembler.ControlledBy.CONVERSATION -> "이 대화 설정에서 변경"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AnimatedVisibility(visible = expanded) {
            MonoBlock(layer.text)
        }

        if (layer.text.isNotEmpty() && !expanded) {
            Text(
                text = "눌러서 실제 내용 보기",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatusDot(included: Boolean) {
    Column(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(
                if (included) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            ),
    ) {}
}

@Composable
private fun MonoBlock(text: String) {
    Text(
        text = text,
        style = CodeTextStyle,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
    )
}

/** A plain-text dump of the whole request, for pasting into a bug report. */
private fun buildDump(assembled: ContextAssembler.Assembled): String = buildString {
    appendLine("# chatie context dump")
    appendLine("conversation: ${assembled.conversation.id}")
    appendLine("model: ${assembled.conversation.model}")
    appendLine("project: ${assembled.project?.name ?: "(none)"}")
    appendLine()
    appendLine("## layers")
    assembled.layers.forEach { layer ->
        appendLine("- [${if (layer.included) "x" else " "}] ${layer.label} (${layer.chars}자): ${layer.reason}")
    }
    appendLine()
    appendLine("## system")
    appendLine(assembled.system ?: "(none)")
    appendLine()
    appendLine("## messages (${assembled.history.sent.size} sent, ${assembled.history.omitted} omitted)")
    assembled.toWireMessages().forEach { m ->
        appendLine("### ${m.role}")
        appendLine(m.content)
    }
}
