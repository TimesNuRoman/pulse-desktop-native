// SPDX-License-Identifier: Apache-2.0
// Pulse — CommandPalette (Ctrl+K overlay). Backdrop blur, palette 640dp wide,
// top 14% of viewport. Squared edges. Per spec section 5.
package com.pulseteam.desktop.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseteam.desktop.ui.common.HDivider
import com.pulseteam.desktop.ui.common.Kbd
import com.pulseteam.desktop.ui.theme.MonoStyle
import com.pulseteam.desktop.ui.theme.PulseColors

sealed class PaletteAction {
    object NewChat : PaletteAction()
    object NewNote : PaletteAction()
    object Sync : PaletteAction()
    data class OpenSettings(val id: String = "settings") : PaletteAction()
    object SwitchModel : PaletteAction()
    object OpenSkills : PaletteAction()
    object ToggleVoice : PaletteAction()
    object ToggleWeb : PaletteAction()
    object ToggleSidebar : PaletteAction()
    object ToggleRightPanel : PaletteAction()
    data class OpenNote(val noteId: String) : PaletteAction()
    data class OpenChat(val id: String) : PaletteAction()
    /** Capture full screen to ~/.pulse/captures/yyyy-MM-dd-HHmmss.png. */
    object TakeScreenshot : PaletteAction()
    /** Capture + OCR; show the text in the chat. */
    object ReadScreenText : PaletteAction()
    /**
     * Capture + OCR + find a word matching [target], then ask SafetyGate
     * to approve the click. Main.kt handles the inline input prompt.
     */
    data class ClickOnText(val target: String) : PaletteAction()
    /**
     * Type [text] char-by-char into the currently-focused control.
     * Routed through SafetyGate so every action shows the confirm dialog.
     */
    data class TypeText(val text: String) : PaletteAction()
    /**
     * Press a key chord (e.g. "Ctrl+Shift+S"). [combo] is parsed by
     * HotkeyParser; invalid combos are rejected by the inline dialog.
     * Routed through SafetyGate.
     */
    data class PressHotkey(val combo: String) : PaletteAction()
    object NoOp : PaletteAction()
}

private data class PaletteCommand(
    val id: String,
    val title: String,
    val section: String,
    val action: PaletteAction,
    val shortcut: String? = null,
    val hint: String? = null,
)

@Composable
fun CommandPalette(
    onDismiss: () -> Unit,
    onAction: (PaletteAction) -> Unit,
    notes: List<com.pulseteam.desktop.data.notes.Note> = emptyList(),
) {
    var query by remember { mutableStateOf("") }
    var activeIndex by remember { mutableStateOf(0) }
    val allCommands = remember(notes) { buildCommands(notes) }

    val filtered by remember(query, allCommands) {
        derivedStateOf { filterCommands(allCommands, query) }
    }

    LaunchedEffect(filtered) {
        if (activeIndex >= filtered.size) activeIndex = (filtered.size - 1).coerceAtLeast(0)
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PulseColors.Drop)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Escape -> { onDismiss(); true }
                        Key.DirectionDown -> { activeIndex = (activeIndex + 1).coerceAtMost(filtered.size - 1); true }
                        Key.DirectionUp -> { activeIndex = (activeIndex - 1).coerceAtLeast(0); true }
                        Key.Enter -> {
                            if (filtered.isNotEmpty()) onAction(filtered[activeIndex].action)
                            else onDismiss()
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Backdrop click target — when user clicks here, dismiss.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onDismiss()  },
        )

        // Palette
        Column(
            modifier = Modifier
                .padding(top = 100.dp)
                .width(640.dp)
                .heightIn(max = 560.dp)
                .background(PulseColors.Bg2, RectangleShape)
                .border(1.dp, PulseColors.BorderStrong, RectangleShape),
        ) {
            // Input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = PulseColors.FgDim,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = TextStyle(color = PulseColors.FgBright, fontSize = 16.sp),
                    cursorBrush = SolidColor(PulseColors.Accent),
                )
                Kbd("esc")
            }
            HDivider()

            // Results
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "No matches for \"${query}\"",
                            color = PulseColors.FgDim,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                } else {
                    val grouped = filtered.groupBy { it.section }
                    var runningIndex = -1
                    grouped.forEach { (section, items) ->
                        item(key = "section-$section") {
                            SectionHeader(section, items.size)
                        }
                        items(items, key = { it.id }) { cmd ->
                            runningIndex++
                            val isActive = runningIndex == activeIndex
                            CommandRow(
                                command = cmd,
                                isActive = isActive,
                                onClick = { onAction(cmd.action) },
                            )
                        }
                    }
                }
            }
            HDivider()

            // Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PulseColors.Bg)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FooterHint("↑↓", "navigate")
                Spacer(Modifier.width(12.dp))
                FooterHint("↵", "open")
                Spacer(Modifier.width(12.dp))
                FooterHint("Ctrl ↵", "run in new chat")
                Spacer(Modifier.width(12.dp))
                FooterHint("esc", "close")
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${filtered.size} results",
                    color = PulseColors.FgDim,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(section: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PulseColors.Bg)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(section.uppercase(), color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), color = PulseColors.FgDisabled, fontSize = 10.sp, style = MonoStyle)
    }
}

@Composable
private fun CommandRow(
    command: PaletteCommand,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isActive) PulseColors.AccentSoft else PulseColors.Bg2
    val fg = if (isActive) PulseColors.Accent else PulseColors.Fg
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RectangleShape)
            .border(
                width = if (isActive) 2.dp else 0.dp,
                color = if (isActive) PulseColors.Accent else PulseColors.Bg2,
                shape = RectangleShape,
            )
            .clickable { onClick()  }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(command.title, color = fg, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        if (command.shortcut != null) {
            Kbd(command.shortcut)
            Spacer(Modifier.width(8.dp))
        }
        if (command.hint != null) {
            Text(command.hint, color = PulseColors.FgDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FooterHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Kbd(key)
        Spacer(Modifier.width(4.dp))
        Text(label, color = PulseColors.FgDim, fontSize = 10.sp)
    }
}

private fun filterCommands(commands: List<PaletteCommand>, query: String): List<PaletteCommand> {
    if (query.isBlank()) return commands
    val q = query.lowercase()
    return commands.filter { it.title.lowercase().contains(q) || it.section.lowercase().contains(q) }
}

private fun buildCommands(notes: List<com.pulseteam.desktop.data.notes.Note>): List<PaletteCommand> {
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    val out = mutableListOf<PaletteCommand>()
    out += PaletteCommand("new-chat", "New chat", "Actions", PaletteAction.NewChat, "Ctrl N")
    out += PaletteCommand("new-note", "New note", "Actions", PaletteAction.NewNote, "Ctrl ⇧ N")
    out += PaletteCommand("sync", "Sync now", "Actions", PaletteAction.Sync, "Ctrl S")
    out += PaletteCommand("settings", "Open settings", "Actions", PaletteAction.OpenSettings(), "Ctrl ,")
    out += PaletteCommand("skills", "Open skills", "Actions", PaletteAction.OpenSkills, "Ctrl ⇧ K")
    out += PaletteCommand("model", "Switch model", "Actions", PaletteAction.SwitchModel, "Ctrl ⇧ M")
    out += PaletteCommand("voice", "Toggle voice", "Actions", PaletteAction.ToggleVoice, "Ctrl ⇧ V")
    out += PaletteCommand("web", "Toggle web search", "Actions", PaletteAction.ToggleWeb, "Ctrl ⇧ W")
    out += PaletteCommand("sidebar", "Toggle sidebar", "Actions", PaletteAction.ToggleSidebar, "Ctrl B")
    out += PaletteCommand("right", "Toggle right panel", "Actions", PaletteAction.ToggleRightPanel, "Ctrl J")
    out += PaletteCommand("a1", "Account", "Settings", PaletteAction.OpenSettings("account"))
    out += PaletteCommand("a2", "Models", "Settings", PaletteAction.OpenSettings("models"))
    out += PaletteCommand("a3", "Hotkeys", "Settings", PaletteAction.OpenSettings("hotkeys"))
    out += PaletteCommand("a4", "Desktop", "Settings", PaletteAction.OpenSettings("desktop"))
    // Desktop control (Phase 1 + Phase 2)
    out += PaletteCommand("screenshot", "Скриншот (save to captures)", "Desktop", PaletteAction.TakeScreenshot, "Ctrl ⇧ S")
    out += PaletteCommand("read-screen", "Что на экране? (OCR)", "Desktop", PaletteAction.ReadScreenText)
    out += PaletteCommand("click-on-text", "Кликни: …", "Desktop", PaletteAction.ClickOnText(""))
    out += PaletteCommand("type-text", "Набери: …", "Desktop", PaletteAction.TypeText(""))
    out += PaletteCommand("press-hotkey", "Хоткей: …", "Desktop", PaletteAction.PressHotkey(""))
    // Real notes from SQLite
    notes.take(20).forEach { n ->
        val age = now - n.updatedAt
        val label = when {
            age < day -> "Today"
            age < day * 7 -> "${age / day}d ago"
            else -> "${age / (day * 7)}w ago"
        }
        out += PaletteCommand(
            id = "note-${n.id}",
            title = n.title,
            section = "Notes",
            action = PaletteAction.OpenNote(n.id),
            hint = label,
        )
    }
    return out
}







