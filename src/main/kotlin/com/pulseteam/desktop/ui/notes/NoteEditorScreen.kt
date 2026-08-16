// SPDX-License-Identifier: Apache-2.0
// Pulse — Note editor. Replaces the chat pane when a note is open.
// Plain Markdown body for now; [[backlinks]] + @mentions are v2.
package com.pulseteam.desktop.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseteam.desktop.data.notes.Note
import com.pulseteam.desktop.ui.common.StatusDot
import com.pulseteam.desktop.ui.theme.MonoStyle
import com.pulseteam.desktop.ui.theme.PulseColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NoteEditorScreen(
    note: Note,
    onClose: () -> Unit,
    onUpdate: (title: String, body: String) -> Unit,
    onOpenNoteByTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var titleField by remember(note.id) { mutableStateOf(TextFieldValue(note.title)) }
    var bodyField by remember(note.id) { mutableStateOf(TextFieldValue(note.body)) }

    // Autosave 600ms after the last edit. Cheap; SQLite is local.
    LaunchedEffect(titleField.text, bodyField.text) {
        kotlinx.coroutines.delay(600)
        if (titleField.text != note.title || bodyField.text != note.body) {
            onUpdate(titleField.text, bodyField.text)
        }
    }

    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }
    val updatedLabel = remember(note.updatedAt) { fmt.format(Date(note.updatedAt)) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(PulseColors.Bg),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(PulseColors.Bg2)
                .border(width = 1.dp, color = PulseColors.Border, shape = RectangleShape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Note", color = PulseColors.FgBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            StatusDot(PulseColors.Accent2, size = 6.dp)
            Spacer(Modifier.width(4.dp))
            Text("local · sqlite + fts4", color = PulseColors.FgDim, fontSize = 11.sp, style = MonoStyle)
            Spacer(Modifier.weight(1f))
            Text("saved · $updatedLabel", color = PulseColors.FgDim, fontSize = 11.sp, style = MonoStyle)
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .background(PulseColors.Bg3, RectangleShape)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("Esc close", color = PulseColors.Fg, fontSize = 11.sp, style = MonoStyle)
            }
        }

        // Title
        BasicTextField(
            value = titleField,
            onValueChange = { titleField = it },
            textStyle = TextStyle(color = PulseColors.FgBright, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(PulseColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            decorationBox = { inner ->
                if (titleField.text.isEmpty()) {
                    Text("Untitled", color = PulseColors.FgDisabled, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                inner()
            },
        )

        HorizontalDivider(thickness = 1.dp, color = PulseColors.Border)

        // Body
        BasicTextField(
            value = bodyField,
            onValueChange = { bodyField = it },
            textStyle = TextStyle(color = PulseColors.Fg, fontSize = 14.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(PulseColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 200.dp)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            decorationBox = { inner ->
                if (bodyField.text.isEmpty()) {
                    Text("Start writing… Markdown supported.\n\nUse [[ to link another note, @ to mention.", color = PulseColors.FgDisabled, fontSize = 14.sp, lineHeight = 22.sp)
                }
                inner()
            },
        )
        // Rendered preview (clickable [[links]] + markdown). Hidden while editing
        // to avoid visual jitter, shown after a short debounce.
        var previewText by remember(note.id) { mutableStateOf<String?>(null) }
        LaunchedEffect(bodyField.text) {
            delay(700)
            previewText = bodyField.text
        }
        if (previewText != null && previewText != bodyField.text && previewText!!.isNotBlank()) {
            HorizontalDivider(thickness = 1.dp, color = PulseColors.Border)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PulseColors.Bg2)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text("PREVIEW", color = PulseColors.FgDim, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
            MarkdownBody(
                text = previewText!!,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PulseColors.Bg2)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                onLinkClick = { title -> onOpenNoteByTitle(title) },
            )
        }

        // Footer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PulseColors.Bg2)
                .border(width = 1.dp, color = PulseColors.Border, shape = RectangleShape)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("${bodyField.text.length} chars", color = PulseColors.FgDim, fontSize = 10.sp, style = MonoStyle)
            Text("${bodyField.text.lineSequence().count()} lines", color = PulseColors.FgDim, fontSize = 10.sp, style = MonoStyle)
            Text("${note.id.take(8)}", color = PulseColors.FgDim, fontSize = 10.sp, style = MonoStyle)
            Spacer(Modifier.weight(1f))
            Text("id: ${note.id}", color = PulseColors.FgDisabled, fontSize = 10.sp, style = MonoStyle)
        }
    }
}
