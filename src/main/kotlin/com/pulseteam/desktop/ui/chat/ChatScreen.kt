// SPDX-License-Identifier: Apache-2.0
// Pulse — ChatScreen (the main window). Layout per spec 4.x:
//   Topbar 40dp | Sidebar 260dp | Main chat (flex) | Right 320dp | StatusBar 28dp
package com.pulseteam.desktop.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import com.pulseteam.desktop.ui.common.HDivider
import com.pulseteam.desktop.ui.common.MonoLabel
import com.pulseteam.desktop.ui.common.StatusDot
import com.pulseteam.desktop.ui.shell.ModelStatus
import com.pulseteam.desktop.ui.shell.RightPanel
import com.pulseteam.desktop.ui.shell.Sidebar
import com.pulseteam.desktop.ui.shell.StatusBar
import com.pulseteam.desktop.ui.shell.Topbar
import com.pulseteam.desktop.ui.theme.MonoStyle
import com.pulseteam.desktop.ui.theme.PulseColors

@Composable
fun ChatScreen(
    chatId: String,
    onOpenSettings: () -> Unit,
    onOpenPalette: () -> Unit,
    modifier: Modifier = Modifier,
    notes: List<com.pulseteam.desktop.data.notes.Note> = emptyList(),
    selectedNoteId: String = "",
    onSelectNote: (String) -> Unit = {},
    onNewNote: () -> Unit = {},
    onOpenNoteByTitle: (String) -> Unit = {},
    centerContent: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    chatViewModel: ChatViewModel,
    userEmail: String? = null,
    syncStatus: com.pulseteam.desktop.data.sync.SyncState = com.pulseteam.desktop.data.sync.SyncState(),
    onSignOut: () -> Unit = {},
    currentNote: com.pulseteam.desktop.data.notes.Note? = null,
    backlinks: List<com.pulseteam.desktop.data.notes.Note> = emptyList(),
    recentNotes: List<com.pulseteam.desktop.data.notes.Note> = emptyList(),
    onOpenBacklink: (String) -> Unit = {},
    isListening: Boolean = false,
    isWebSearchOn: Boolean = false,
    onToggleVoice: (Boolean) -> Unit = { _ -> },
    onAttachFile: () -> Unit = {},
    onToggleWeb: (Boolean) -> Unit = { _ -> },
    onSyncNow: () -> Unit = {},
    onShowLastEvent: () -> Unit = {},
    lastEvent: String? = null,
    updateInfo: com.pulseteam.desktop.data.update.UpdateInfo? = null,
    onDownloadUpdate: (() -> Unit)? = null,
) {
    val messages by chatViewModel.messages.collectAsState()
    val webStatus by chatViewModel.webStatus.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        Topbar(
            onOpenPalette = onOpenPalette,
            onOpenSettings = onOpenSettings,
            onSyncNow = onSyncNow,
            onShowLastEvent = onShowLastEvent,
            lastEvent = lastEvent,
            updateInfo = updateInfo,
            onDownloadUpdate = onDownloadUpdate,
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Sidebar(
                modifier = Modifier.width(260.dp).fillMaxHeight(),
                selectedId = selectedNoteId,
                onSelect = onSelectNote,
                onNewChat = {
                    // Clear the conversation in-place. The chatId is the same
                    // "welcome" session; the user is signalling "start over".
                    chatViewModel.newChat()
                },
                onNewNote = onNewNote,
                onOpenSettings = onOpenSettings,
                notes = notes,
                userEmail = userEmail,
                onSignOut = onSignOut,
            )
            if (centerContent != null) {
                centerContent()
            } else {
                ChatPane(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    messages = messages,
                    onSend = { chatViewModel.sendMessage(it) },
                    onLinkClick = onOpenNoteByTitle,
                    onToggleVoice = onToggleVoice,
                    onAttachFile = onAttachFile,
                    onToggleWeb = onToggleWeb,
                    isListening = isListening,
                    isWebSearchOn = isWebSearchOn,
                    webStatus = webStatus,
                )
            }
            RightPanel(
                modifier = Modifier.width(320.dp).fillMaxHeight(),
                currentNote = currentNote,
                backlinks = backlinks,
                recentNotes = recentNotes,
                onOpenBacklink = onOpenBacklink,
            )
        }
        StatusBar(
            modelName = "qwen2.5-coder:7b",
            modelStatus = ModelStatus.Ready,
            sync = if (syncStatus.lastSyncAt > 0) "Synced ${formatRelative(syncStatus.lastSyncAt)}" else "Not synced yet",
            usedMb = 4.2,
            totalMb = 100.0,
            cursor = "Ln 1, Col 1",
        )
    }
}

private fun formatRelative(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val s = diff / 1000
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        else -> "${s / 86400}d ago"
    }
}

@Composable
private fun ChatPane(
    messages: List<ChatMessage>,
    onSend: (String) -> Unit,
    onLinkClick: (String) -> Unit = {},
    onToggleVoice: (Boolean) -> Unit = { _ -> },
    onAttachFile: () -> Unit = {},
    onToggleWeb: (Boolean) -> Unit = { _ -> },
    isListening: Boolean = false,
    isWebSearchOn: Boolean = false,
    webStatus: String? = null,
    modifier: Modifier = Modifier,
) {
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
            Text("Pulse Chat", color = PulseColors.FgBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            StatusDot(PulseColors.Green, size = 6.dp)
            Spacer(Modifier.width(4.dp))
            Text("qwen2.5-coder:7b", color = PulseColors.FgDim, fontSize = 11.sp, style = MonoStyle)
        }

        // Messages
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageRow(msg, onLinkClick)
            }
        }

        HDivider()

        // Composer
        Composer(
            onSend = onSend,
            onToggleVoice = onToggleVoice,
            onAttachFile = onAttachFile,
            onToggleWeb = onToggleWeb,
            isListening = isListening,
            isWebSearchOn = isWebSearchOn,
            webStatus = webStatus,
        )
    }
}

@Composable
private fun MessageRow(message: ChatMessage, onLinkClick: (String) -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.from == "user") Arrangement.End else Arrangement.Start,
    ) {
        if (message.from == "ai") {
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(28.dp)
                    .background(PulseColors.Accent, RectangleShape)
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("P", color = PulseColors.Bg, fontSize = 14.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .background(
                    if (message.from == "user") PulseColors.Bg2 else PulseColors.Bg,
                    RectangleShape,
                )
                .border(1.dp, PulseColors.Border, RectangleShape)
                .padding(12.dp),
        ) {
            if (message.from == "user") {
                Text("You", color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
            }
            if (message.from == "ai") {
                com.pulseteam.desktop.ui.notes.MarkdownBody(
                    text = message.text,
                    style = androidx.compose.ui.text.TextStyle(color = PulseColors.Fg, fontSize = 13.sp, lineHeight = 20.sp),
                    onLinkClick = onLinkClick,
                )
            } else {
                Text(
                    text = message.text,
                    color = PulseColors.Fg,
                    fontSize = 13.sp,
                )
            }
        }
        if (message.from == "user") {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(28.dp)
                    .background(PulseColors.Accent2, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("R", color = PulseColors.Bg, fontSize = 14.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun Composer(
    onSend: (String) -> Unit,
    onToggleVoice: (Boolean) -> Unit = { _ -> },
    onAttachFile: () -> Unit = {},
    onToggleWeb: (Boolean) -> Unit = { _ -> },
    isListening: Boolean = false,
    isWebSearchOn: Boolean = false,
    webStatus: String? = null,
) {
    var text by remember { mutableStateOf(TextFieldValue("")) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PulseColors.Bg)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, PulseColors.BorderStrong, RectangleShape)
                .background(PulseColors.BgInput, RectangleShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).heightIn(min = 32.dp, max = 200.dp),
                textStyle = TextStyle(color = PulseColors.Fg, fontSize = 13.sp),
                cursorBrush = SolidColor(PulseColors.Accent),
                decorationBox = { inner ->
                    if (text.text.isEmpty()) {
                        Text(if (isListening) "Listening…" else "Ask anything…  /command  @note  Shift Enter = newline", color = PulseColors.FgDim, fontSize = 13.sp)
                    }
                    inner()
                },
            )
            Spacer(Modifier.width(8.dp))
            ComposerButton(
                icon = Icons.Default.GraphicEq,
                contentDescription = if (isListening) "Stop voice" else "Voice",
                active = isListening,
                onClick = { onToggleVoice(!isListening) },
            )
            Spacer(Modifier.width(4.dp))
            ComposerButton(
                icon = Icons.Default.Link,
                contentDescription = "Attach",
                onClick = { onAttachFile() },
            )
            Spacer(Modifier.width(4.dp))
            ComposerButton(
                icon = Icons.Default.Public,
                contentDescription = "Web search",
                active = isWebSearchOn,
                onClick = { onToggleWeb(!isWebSearchOn) },
            )
            Spacer(Modifier.width(8.dp))
            // Send button
            Row(
                modifier = Modifier
                    .background(
                        if (text.text.isBlank()) PulseColors.Bg3 else PulseColors.Accent,
                        RectangleShape,
                    )
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .clickable(enabled = text.text.isNotBlank()) {
                        onSend(text.text)
                        text = TextFieldValue("")
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Send",
                    color = if (text.text.isBlank()) PulseColors.FgDim else PulseColors.Bg,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "↵",
                    color = if (text.text.isBlank()) PulseColors.FgDim else PulseColors.Bg,
                    fontSize = 11.sp,
                    style = MonoStyle,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Shift+↵ newline", color = PulseColors.FgDim, fontSize = 10.sp)
            Text("/ commands", color = PulseColors.FgDim, fontSize = 10.sp)
            Text("@ mention note", color = PulseColors.FgDim, fontSize = 10.sp)
            if (webStatus != null) {
                // Compact pill that shows live web-search status. Renders
                // between the hint text and the right-aligned note.
                Row(
                    modifier = Modifier
                        .background(PulseColors.AccentSoft, RectangleShape)
                        .border(1.dp, PulseColors.Accent, RectangleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        webStatus,
                        color = PulseColors.Accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Your password is the encryption key", color = PulseColors.FgDisabled, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ComposerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(28.dp)
            .height(28.dp)
            .background(if (active) PulseColors.AccentSoft else PulseColors.Bg3, RectangleShape)
            .clickable { onClick()  },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) PulseColors.Accent else PulseColors.FgDim,
            modifier = Modifier.size(14.dp),
        )
    }
}

private fun Modifier.size(value: androidx.compose.ui.unit.Dp): Modifier =
    this.width(value).height(value)

/* ============================================================ Preview data */

data class ChatMessage(
    val id: String,
    val from: String, // "user" | "ai"
    val text: String,
)

private fun previewMessages(): List<ChatMessage> = listOf(
    ChatMessage(
        id = "1",
        from = "ai",
        text = "Hi Roman. Pulse is local-first — your notes, chats, and model stay on this device. No account, no telemetry. What do you want to do first?",
    ),
    ChatMessage(
        id = "2",
        from = "user",
        text = "Show me how the [[backlinks]] work in the new build.",
    ),
    ChatMessage(
        id = "3",
        from = "ai",
        text = "Backlinks are automatic. Type [[ in any note to link another note, and the linked note will list every reference in its Backlinks panel on the right. You can also use @ to mention a note without creating a link.",
    ),
    ChatMessage(
        id = "4",
        from = "user",
        text = "And what about sync? Is it end-to-end encrypted?",
    ),
    ChatMessage(
        id = "5",
        from = "ai",
        text = "Yes. Sync uses AES-256-GCM with a key derived from your password via scrypt(N=2^15, r=8, p=1). The server only sees ciphertext + a 12-byte nonce + the 16-byte tag. Your password is never sent to the server.",
    ),
)









