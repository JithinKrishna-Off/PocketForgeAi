package com.pocketforge.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal Markdown rendering for streamed chat content:
 * headings, bold/italic, inline code, fenced code blocks and bullet lists.
 * Not a full Markdown parser by design - it renders what models actually emit
 * during chat responses.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val base = MaterialTheme.typography.bodyMedium
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier) {
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") && line.trim().let { it.startsWith("```") } -> {
                    val lang = line.trim().removePrefix("```").trim()
                    i++
                    val code = StringBuilder()
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.append(lines[i]).append('\n')
                        i++
                    }
                    i++ // closing fence or EOF
                    CodeBlock(code.toString().trimEnd('\n'), if (lang.isNotEmpty()) lang else null)
                }
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length.coerceAtMost(3)
                    val text = line.dropWhile { it == '#' }.trim()
                    Text(
                        text = inline(text),
                        fontSize = when (level) {
                            1 -> 20.sp
                            2 -> 18.sp
                            else -> 16.sp
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = base.fontFamily,
                    )
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    Text(text = inline("  •  " + line.trimStart().drop(2)))
                }
                line.trimStart().matches(Regex("\\d+\\. .*")) -> {
                    Text(text = inline(line.trimStart()))
                }
                line.isBlank() -> Text(" ", lineHeight = 6.sp)
                else -> Text(text = inline(line))
            }
            i++
        }
    }
}

@Composable
private fun CodeBlock(code: String, lang: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
    ) {
        if (lang != null) {
            Text(
                lang,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val boldMarker = Regex("\\*\\*(.+?)\\*\\*")
private val inlineCode = Regex("`(.+?)`")

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var pos = 0
    val matches = boldMarker.findAll(text).map { it.range to "b" } +
        inlineCode.findAll(text).map { it.range to "c" }

    for ((range, kind) in matches.sortedBy { it.first.first }) {
        if (range.first < pos) continue
        append(text.substring(pos, range.first))
        when (kind) {
            "b" -> withStyle2(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(range)) }
            else -> withStyle2(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0x22999999),
                ),
            ) { append(text.substring(range)) }
        }
        pos = range.last + 1
    }
    if (pos < text.length) append(text.substring(pos))
}

private inline fun AnnotatedString.Builder.withStyle2(style: SpanStyle, block: () -> Unit) {
    val span = pushStyle(style)
    block()
    pop()
}
