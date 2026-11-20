package co.chatie.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.chatie.app.ui.theme.CodeTextStyle

/**
 * Renders a finished message.
 *
 * [parseMarkdown] runs inside `remember(source)`, so a message is parsed once
 * and never again while it stays on screen — scrolling a long chat does no
 * parsing work at all. Links come through as [androidx.compose.ui.text.LinkAnnotation],
 * which Text handles itself.
 */
@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val blocks = remember(source) { parseMarkdown(source) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = block.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = when (block.level) {
                        1 -> 22.sp
                        2 -> 19.sp
                        3 -> 17.sp
                        else -> 16.sp
                    },
                )

                is MdBlock.Paragraph -> Text(block.text)

                is MdBlock.Code -> CodeBlock(block)

                is MdBlock.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row {
                            Text(
                                text = if (block.ordered) "${index + 1}. " else "•  ",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(item, Modifier.weight(1f))
                        }
                    }
                }

                is MdBlock.Quote -> Row {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(20.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(block.text, Modifier.padding(start = 10.dp))
                }

                MdBlock.Rule -> HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        block.language?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Text(
            text = block.code,
            style = CodeTextStyle,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}
