package co.chatie.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * A small markdown parser, written here on purpose.
 *
 * Markdown libraries are the usual reason a chat UI stutters: they re-parse the
 * whole message on every token. This one is a pure function from text to blocks
 * so a finished message is parsed exactly once and cached, and a streaming
 * message is not parsed at all — the chat screen renders raw text until the
 * stream ends.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
    data class Paragraph(val text: AnnotatedString) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class Bullet(val items: List<AnnotatedString>, val ordered: Boolean) : MdBlock
    data class Quote(val text: AnnotatedString) : MdBlock
    data object Rule : MdBlock
}

private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
private val ORDERED = Regex("""^\s*\d+[.)]\s+(.*)$""")
private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val FENCE = Regex("""^\s*```\s*([A-Za-z0-9+#._-]*)\s*$""")
private val RULE = Regex("""^\s*([-*_])\s*\1\s*\1[\s\-*_]*$""")
private val QUOTE = Regex("""^\s*>\s?(.*)$""")

fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    var listItems = mutableListOf<String>()
    var listOrdered = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(parseInline(paragraph.joinToString("\n")))
            paragraph.clear()
        }
    }

    fun flushList() {
        if (listItems.isNotEmpty()) {
            blocks += MdBlock.Bullet(listItems.map(::parseInline), listOrdered)
            listItems = mutableListOf()
        }
    }

    fun flushAll() {
        flushParagraph()
        flushList()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        val fence = FENCE.matchEntire(line)
        if (fence != null) {
            flushAll()
            val language = fence.groupValues[1].ifBlank { null }
            val code = StringBuilder()
            i++
            while (i < lines.size && FENCE.matchEntire(lines[i]) == null) {
                code.appendLine(lines[i])
                i++
            }
            // An unterminated fence (a block still streaming) is shown as-is.
            i++
            blocks += MdBlock.Code(language, code.toString().trimEnd('\n'))
            continue
        }

        val heading = HEADING.matchEntire(line)
        val quote = QUOTE.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        val ordered = ORDERED.matchEntire(line)

        when {
            line.isBlank() -> flushAll()

            RULE.matchEntire(line) != null -> {
                flushAll()
                blocks += MdBlock.Rule
            }

            heading != null -> {
                flushAll()
                blocks += MdBlock.Heading(
                    level = heading.groupValues[1].length,
                    text = parseInline(heading.groupValues[2]),
                )
            }

            quote != null -> {
                flushAll()
                blocks += MdBlock.Quote(parseInline(quote.groupValues[1]))
            }

            bullet != null -> {
                flushParagraph()
                if (listOrdered && listItems.isNotEmpty()) flushList()
                listOrdered = false
                listItems += bullet.groupValues[1]
            }

            ordered != null -> {
                flushParagraph()
                if (!listOrdered && listItems.isNotEmpty()) flushList()
                listOrdered = true
                listItems += ordered.groupValues[1]
            }

            else -> {
                flushList()
                paragraph += line
            }
        }
        i++
    }
    flushAll()
    return blocks
}

private val LINK_STYLE = SpanStyle(
    color = Color(0xFF4C7DF0),
    textDecoration = TextDecoration.Underline,
)

private val INLINE_CODE = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22808080))

/**
 * Inline spans: `code`, **bold**, *italic*, ~~strike~~, [text](url).
 * Single pass, no backtracking, so cost is linear in the text length.
 */
fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0

    fun delimited(open: String, close: String = open): Pair<String, Int>? {
        if (!text.startsWith(open, i)) return null
        val start = i + open.length
        val end = text.indexOf(close, start)
        if (end < 0 || end == start) return null
        return text.substring(start, end) to end + close.length
    }

    while (i < text.length) {
        val isItalicMark =
            (text.startsWith("*", i) || text.startsWith("_", i)) && !text.startsWith("**", i)

        when {
            text.startsWith("`", i) -> {
                val found = delimited("`")
                if (found != null) {
                    withStyle(INLINE_CODE) { append(found.first) }
                    i = found.second
                } else {
                    append(text[i]); i++
                }
            }

            text.startsWith("**", i) -> {
                val found = delimited("**")
                if (found != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(parseInline(found.first))
                    }
                    i = found.second
                } else {
                    append(text[i]); i++
                }
            }

            text.startsWith("~~", i) -> {
                val found = delimited("~~")
                if (found != null) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(parseInline(found.first))
                    }
                    i = found.second
                } else {
                    append(text[i]); i++
                }
            }

            isItalicMark -> {
                val mark = text[i].toString()
                val found = delimited(mark)
                if (found != null) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(parseInline(found.first))
                    }
                    i = found.second
                } else {
                    append(text[i]); i++
                }
            }

            text.startsWith("[", i) -> {
                val close = text.indexOf(']', i)
                val parenOpen = if (close >= 0) close + 1 else -1
                val parenClose = if (parenOpen in text.indices && text[parenOpen] == '(') {
                    text.indexOf(')', parenOpen)
                } else {
                    -1
                }
                if (close > i && parenClose > parenOpen) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(parenOpen + 1, parenClose)
                    // LinkAnnotation makes the span tappable inline, with no
                    // click plumbing in the renderer.
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(style = LINK_STYLE))) {
                        append(label)
                    }
                    i = parenClose + 1
                } else {
                    append(text[i]); i++
                }
            }

            else -> {
                append(text[i]); i++
            }
        }
    }
}
