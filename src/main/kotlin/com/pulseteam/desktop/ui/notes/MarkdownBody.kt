// SPDX-License-Identifier: Apache-2.0
// Pulse — tiny markdown + [[link]] renderer. Hand-rolled to avoid pulling
// a 200KB library for the 6 syntaxes we actually use.
//
// Supported inline:
//   **bold**           -> bold
//   *italic*           -> italic
//   `code`             -> mono with Bg3 background
//   [[Title]]          -> clickable, opens note by title
//   [[Title|body]]     -> same, but body is the displayed text
//
// Block-level:
//   # Heading 1
//   ## Heading 2
//   blank line -> paragraph break
//
// Everything else is plain text.
package com.pulseteam.desktop.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseteam.desktop.ui.theme.PulseColors

data class NoteLinkToken(
    val title: String,
    val displayed: String,
    val start: Int,
    val end: Int,
)

/** Parse `[[Title]]` / `[[Title|text]]` tokens from a string. */
fun extractNoteLinks(text: String): List<NoteLinkToken> {
    val out = mutableListOf<NoteLinkToken>()
    val rx = Regex("""\[\[([^\[\]\n]+?)\]\]""")
    for (m in rx.findAll(text)) {
        val raw = m.groupValues[1].trim()
        if (raw.isEmpty()) continue
        val pipe = raw.indexOf('|')
        val title: String
        val displayed: String
        if (pipe < 0) { title = raw; displayed = raw }
        else {
            title = raw.substring(0, pipe).trim()
            displayed = raw.substring(pipe + 1).trim().ifEmpty { title }
        }
        if (title.isEmpty()) continue
        out += NoteLinkToken(title, displayed, m.range.first, m.range.last + 1)
    }
    return out
}

/**
 * Build an AnnotatedString with bold/italic/code/note-link styles inlined.
 * `linkStyle` is applied to [[link]] spans so callers can highlight them
 * (e.g. accent color, underline).
 */
fun annotateInline(
    text: String,
    baseStyle: SpanStyle,
    linkStyle: SpanStyle = baseStyle.copy(
        color = PulseColors.Accent,
        textDecoration = TextDecoration.Underline,
    ),
): AnnotatedString = buildAnnotatedString {
    val tokens = extractNoteLinks(text)
    if (tokens.isEmpty()) { appendInline(this, text, baseStyle); return@buildAnnotatedString }
    var i = 0
    for (t in tokens) {
        if (i < t.start) appendInline(this, text.substring(i, t.start), baseStyle)
        pushStyle(linkStyle)
        append(t.displayed)
        pop()
        i = t.end
    }
    if (i < text.length) appendInline(this, text.substring(i), baseStyle)
}

private fun appendInline(sb: androidx.compose.ui.text.AnnotatedString.Builder, s: String, base: SpanStyle) {
    val rx = Regex("""(\*\*[^*\n]+\*\*|\*[^*\n]+\*|`[^`\n]+`)""")
    var i = 0
    for (m in rx.findAll(s)) {
        if (i < m.range.first) sb.append(s.substring(i, m.range.first))
        val raw = m.value
        when {
            raw.startsWith("**") -> {
                sb.pushStyle(base.copy(fontWeight = FontWeight.Bold))
                sb.append(raw.removeSurrounding("**"))
                sb.pop()
            }
            raw.startsWith("`") -> {
                sb.pushStyle(base.copy(
                    fontFamily = FontFamily.Monospace,
                    background = PulseColors.Bg3,
                ))
                sb.append(raw.removeSurrounding("`"))
                sb.pop()
            }
            raw.startsWith("*") -> {
                sb.pushStyle(base.copy(fontWeight = FontWeight.Normal)) // italic not in M3 default
                sb.append(raw.removeSurrounding("*"))
                sb.pop()
            }
        }
        i = m.range.last + 1
    }
    if (i < s.length) sb.append(s.substring(i))
}

@Composable
fun MarkdownBody(
    text: String,
    style: TextStyle = TextStyle(color = PulseColors.Fg, fontSize = 14.sp, lineHeight = 22.sp),
    onLinkClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val baseSpan = style.toSpanStyle()
    val blocks = splitBlocks(text)
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        for ((idx, block) in blocks.withIndex()) {
            if (idx > 0) Spacer(Modifier.padding(top = 4.dp))
            when {
                block.startsWith("# ") -> {
                    BasicText(
                        text = annotateInline(block.removePrefix("# "), baseSpan.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp)),
                        style = style.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.FgBright),
                    )
                }
                block.startsWith("## ") -> {
                    BasicText(
                        text = annotateInline(block.removePrefix("## "), baseSpan.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp)),
                        style = style.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.FgBright),
                    )
                }
                else -> {
                    val annotated = annotateInline(block, baseSpan)
                    ClickableText(annotated = annotated, onLinkClick = onLinkClick)
                }
            }
        }
    }
}

@Composable
private fun ClickableText(annotated: AnnotatedString, onLinkClick: (String) -> Unit) {
    val tokens = extractNoteLinks(annotated.text)
    if (tokens.isEmpty()) {
        BasicText(text = annotated, style = annotated.let { TextStyle(color = PulseColors.Fg, fontSize = 14.sp, lineHeight = 22.sp) })
        return
    }
    // Build clickable ranges
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = TextStyle(color = PulseColors.Fg, fontSize = 14.sp, lineHeight = 22.sp),
        onClick = { offset ->
            val t = tokens.firstOrNull { offset >= it.start && offset < it.end }
            if (t != null) onLinkClick(t.title)
        },
    )
}

private fun splitBlocks(text: String): List<String> {
    val out = mutableListOf<String>()
    val para = StringBuilder()
    for (line in text.split("\n")) {
        if (line.isBlank()) {
            if (para.isNotEmpty()) { out += para.toString().trimEnd(); para.clear() }
        } else {
            if (para.isNotEmpty()) para.append('\n')
            para.append(line)
        }
    }
    if (para.isNotEmpty()) out += para.toString().trimEnd()
    return out.ifEmpty { listOf("") }
}
