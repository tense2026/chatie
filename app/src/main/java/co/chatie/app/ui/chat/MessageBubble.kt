package co.chatie.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.chatie.app.data.Message
import co.chatie.app.data.MessageStatus
import co.chatie.app.data.Role
import co.chatie.app.ui.common.MarkdownText
import java.util.Locale

/**
 * One committed message.
 *
 * Takes only immutable values, so Compose can skip it: scrolling or streaming
 * never recomposes a bubble whose text has not changed.
 */
@Composable
fun MessageBubble(message: Message, modifier: Modifier = Modifier) {
    when (message.role) {
        Role.USER -> UserBubble(message, modifier)
        Role.ASSISTANT -> AssistantBubble(message, modifier)
        Role.SYSTEM -> Unit
    }
}

@Composable
private fun UserBubble(message: Message, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(message.content)
        }
    }
}

@Composable
private fun AssistantBubble(message: Message, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        MarkdownText(message.content)

        if (message.status == MessageStatus.ERROR) {
            Text(
                text = message.error ?: "응답이 중간에 끊겼습니다.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        MessageFooter(message)
    }
}

/** Model, tokens and credits for the turn — the things worth auditing later. */
@Composable
private fun MessageFooter(message: Message) {
    val parts = buildList {
        message.model?.let { add(it) }
        val tokens = listOfNotNull(message.promptTokens, message.completionTokens)
        if (tokens.size == 2) add("${tokens[0]}+${tokens[1]} 토큰")
        message.credits?.let { add("${formatCredits(it)} 크레딧") }
    }
    if (parts.isEmpty()) return

    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier.padding(top = 6.dp),
    )
}

private fun formatCredits(value: Double): String =
    if (value >= 10) value.toInt().toString() else String.format(Locale.US, "%.2f", value)
