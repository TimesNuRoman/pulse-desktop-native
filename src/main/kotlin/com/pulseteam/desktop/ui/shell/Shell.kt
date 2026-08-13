// SPDX-License-Identifier: Apache-2.0
// Pulse — Shell components: Topbar, Sidebar, RightPanel, StatusBar.
// All rectangular (no rounded corners), Tokyo Night dark.
package com.pulseteam.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import com.pulseteam.desktop.data.update.UpdateInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import com.pulseteam.desktop.ui.common.HDivider
import com.pulseteam.desktop.ui.common.Kbd
import com.pulseteam.desktop.ui.common.MonoLabel
import com.pulseteam.desktop.ui.common.StatusDot
import com.pulseteam.desktop.ui.common.StatusItem
import com.pulseteam.desktop.ui.common.StatusSeparator
import com.pulseteam.desktop.ui.common.VDivider
import com.pulseteam.desktop.ui.theme.MonoStyle
import com.pulseteam.desktop.ui.theme.PulseColors

/* ============================================================ TOPBAR */

@Composable
fun Topbar(
    onOpenPalette: () -> Unit,
    onOpenSettings: () -> Unit,
    onSyncNow: () -> Unit = {},
    lastEvent: String? = null,
    updateInfo: UpdateInfo? = null,
    onDownloadUpdate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(PulseColors.Bg2.copy(alpha = 0.85f))
            .border(width = 1.dp, color = PulseColors.Border, shape = RectangleShape),
    ) {
        // Topbar lives in a Row so the brand + workspace + search + actions line up
        // horizontally. The whole bar is a drag region (window.draggable area) — the
        // inner Row is laid out by left/right Spacer with weight so the search input
        // gets the middle.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Brand
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(PulseColors.Accent, RectangleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "P",
                        color = PulseColors.Bg,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Pulse",
                    color = PulseColors.FgBright,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.width(16.dp))
            Text(
                text = "Team workspace",
                color = PulseColors.FgDim,
                fontSize = 12.sp,
            )

            Spacer(Modifier.weight(1f))

            // Search field (faux — real search opens a separate screen)
            Row(
                modifier = Modifier
                    .width(280.dp)
                    .height(28.dp)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .background(PulseColors.BgInput, RectangleShape)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = PulseColors.FgDim,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Search\u2026",
                    color = PulseColors.FgDim,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Kbd("Ctrl K")
            }

            Spacer(Modifier.width(12.dp))

            // Update-available pill: small, unobtrusive, only when the
            // UpdateChecker detected a newer version. Click opens the
            // browser to the manifest's URL.
            if (updateInfo != null && onDownloadUpdate != null) {
                Row(
                    modifier = Modifier
                        .background(PulseColors.AccentSoft, RectangleShape)
                        .border(1.dp, PulseColors.Accent, RectangleShape)
                        .clickable { onDownloadUpdate() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("v${updateInfo.version} available", color = PulseColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(4.dp))
                    Text("Download", color = PulseColors.Bg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
            }

            TopbarAction(
                icon = Icons.Default.Sync,
                contentDescription = "Sync now${if (lastEvent != null) " — $lastEvent" else ""}",
                onClick = onSyncNow,
            )
            Spacer(Modifier.width(8.dp))
            TopbarAction(icon = Icons.Default.Notifications, contentDescription = "Notifications")
            Spacer(Modifier.width(8.dp))
            TopbarAction(icon = Icons.Default.Settings, contentDescription = "Settings", onClick = onOpenSettings)
            Spacer(Modifier.width(12.dp))
            // Avatar (small square with initial)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(PulseColors.Accent2, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "R",
                    color = PulseColors.Bg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun TopbarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(PulseColors.Bg3, RectangleShape)
            .let { if (onClick != null) it.clickable { onClick() } else it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = PulseColors.FgDim,
            modifier = Modifier.size(14.dp),
        )
    }
}

/* ============================================================ SIDEBAR */

@Composable
fun Sidebar(
    selectedId: String,
    onSelect: (String) -> Unit,
    onNewChat: () -> Unit,
    onNewNote: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    notes: List<com.pulseteam.desktop.data.notes.Note> = emptyList(),
    userEmail: String? = null,
    onSignOut: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(PulseColors.Bg2)
            .border(width = 1.dp, color = PulseColors.Border, shape = RectangleShape)
            .padding(vertical = 8.dp),
    ) {
        // New chat / New note
        Row(modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth()) {
            SidebarAction(label = "New chat", shortcut = "Ctrl N", onClick = onNewChat, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            SidebarAction(label = "New note", shortcut = "Ctrl Shift N", onClick = onNewNote, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        // Filter input (live client-side filter on top of the notes list)
        var filter by remember { mutableStateOf(TextFieldValue("")) }
        val filtered = remember(filter.text, notes) {
            if (filter.text.isBlank()) notes
            else notes.filter {
                it.title.contains(filter.text, ignoreCase = true) ||
                    it.body.contains(filter.text, ignoreCase = true)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(28.dp)
                .border(1.dp, PulseColors.Border, RectangleShape)
                .background(PulseColors.BgInput, RectangleShape)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                textStyle = TextStyle(color = PulseColors.Fg, fontSize = 12.sp),
                cursorBrush = SolidColor(PulseColors.Accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    if (filter.text.isEmpty()) {
                        Text("Filter\u2026", color = PulseColors.FgDim, fontSize = 12.sp)
                    }
                    innerTextField()
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        // Notes list
        LazyColumn(modifier = Modifier.weight(1f)) {
            item { SidebarSectionHeader("Notes · ${filtered.size}/${notes.size}") }
            if (filtered.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Text(
                            if (notes.isEmpty()) "No notes yet. Press Ctrl+Shift+N."
                            else "No notes match \"${filter.text}\".",
                            color = PulseColors.FgDim,
                            fontSize = 11.sp,
                        )
                    }
                }
            } else {
                items(filtered, key = { it.id }) { note ->
                    NoteSidebarItem(note, note.id == selectedId, onSelect)
                }
            }
        }

        HDivider()
        // Footer: avatar + email + sign out + settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(PulseColors.Accent2, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (userEmail?.firstOrNull()?.uppercase() ?: "R").toString(),
                    color = PulseColors.Bg, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                userEmail ?: "Not signed in",
                color = PulseColors.Fg, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (userEmail != null) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(PulseColors.Bg3, RectangleShape)
                        .clickable { onSignOut()  },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⎋", color = PulseColors.FgDim, fontSize = 12.sp)
                }
                Spacer(Modifier.width(4.dp))
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(PulseColors.Bg3, RectangleShape)
                    .clickable { onOpenSettings()  },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = PulseColors.FgDim,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun SidebarAction(
    label: String,
    shortcut: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(48.dp)
            .background(PulseColors.Bg3, RectangleShape)
            .border(1.dp, PulseColors.Border, RectangleShape)
            .clickable { onClick()  }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(label, color = PulseColors.Fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(shortcut, color = PulseColors.FgDim, fontSize = 10.sp, style = MonoStyle)
    }
}

@Composable
private fun SidebarSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = PulseColors.FgDim,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun NoteSidebarItem(note: com.pulseteam.desktop.data.notes.Note, isSelected: Boolean, onClick: (String) -> Unit) {
    val bg = if (isSelected) PulseColors.AccentSoft else PulseColors.Bg2
    val fg = if (isSelected) PulseColors.Accent else PulseColors.Fg
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RectangleShape)
            .clickable { onClick(note.id)  }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(note.title, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (note.preview.isNotEmpty()) {
            Text(note.preview, color = PulseColors.FgDim, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/* ============================================================ RIGHT PANEL */

@Composable
fun RightPanel(
    modifier: Modifier = Modifier,
    contextCount: Int = 0,
    currentNote: com.pulseteam.desktop.data.notes.Note? = null,
    backlinks: List<com.pulseteam.desktop.data.notes.Note> = emptyList(),
    recentNotes: List<com.pulseteam.desktop.data.notes.Note> = emptyList(),
    onOpenBacklink: (String) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(PulseColors.Bg2)
            .border(width = 1.dp, color = PulseColors.Border, shape = RectangleShape)
            .padding(vertical = 12.dp),
    ) {
        RightSection("Attached context", "$contextCount") {
            if (contextCount == 0) {
                Text(
                    "Drag a note here to attach.",
                    color = PulseColors.FgDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    "$contextCount items attached",
                    color = PulseColors.FgDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        HDivider()
        RightSection("Backlinks", if (currentNote == null) "\u2014" else "${backlinks.size}") {
            if (currentNote == null) {
                Text(
                    "Open a note to see backlinks.",
                    color = PulseColors.FgDim, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            } else if (backlinks.isEmpty()) {
                Text(
                    "No notes link to \u201c${currentNote.title}\u201d yet.",
                    color = PulseColors.FgDim, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            } else {
                for (b in backlinks) {
                    val firstLine = b.body.lineSequence().firstOrNull()?.trim().orEmpty().take(80)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .border(1.dp, PulseColors.Border, RectangleShape)
                            .background(PulseColors.Bg, RectangleShape)
                            .clickable { onOpenBacklink(b.id) }
                            .padding(8.dp),
                    ) {
                        Column {
                            Text(
                                text = b.title,
                                color = PulseColors.Fg,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            if (firstLine.isNotEmpty()) {
                                Text(
                                    text = firstLine,
                                    color = PulseColors.FgDim,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        HDivider()
        RightSection("Suggested next") {
            val ctxNote = currentNote
            if (ctxNote != null && backlinks.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(
                        "Related via backlinks:",
                        color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    backlinks.take(3).forEach { b ->
                        Text(
                            "\u2022 ${b.title}",
                            color = PulseColors.Fg, fontSize = 11.sp, lineHeight = 16.sp,
                            maxLines = 1,
                        )
                    }
                }
            } else if (ctxNote != null) {
                Text(
                    "No notes link to \u201c${ctxNote.title}\u201d yet.",
                    color = PulseColors.FgDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            } else if (recentNotes.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(
                        "Recent notes:",
                        color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    recentNotes.take(3).forEach { n ->
                        Text(
                            "\u2022 ${n.title}",
                            color = PulseColors.Fg, fontSize = 11.sp, lineHeight = 16.sp,
                            maxLines = 1,
                        )
                    }
                }
            } else {
                Text(
                    "Open or create a note to see suggestions.",
                    color = PulseColors.FgDim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        if (contextCount > 0) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RightSection(
    title: String,
    count: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = PulseColors.FgDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (count != null) {
                Box(
                    modifier = Modifier
                        .background(PulseColors.Bg3, RectangleShape)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(count, color = PulseColors.FgDim, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        content()
    }
}

/* ============================================================ STATUS BAR */

@Composable
fun StatusBar(
    modelName: String,
    modelStatus: ModelStatus,
    sync: String,
    usedMb: Double,
    totalMb: Double,
    cursor: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(PulseColors.Bg2)
            .border(width = 1.dp, color = PulseColors.Border, shape = RectangleShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusItem(
            text = when (modelStatus) {
                ModelStatus.Ready -> "Local model ready"
                ModelStatus.Loading -> "Loading model\u2026"
                ModelStatus.Error -> "Model error"
            },
            leading = {
                StatusDot(
                    color = when (modelStatus) {
                        ModelStatus.Ready -> PulseColors.Green
                        ModelStatus.Loading -> PulseColors.Warn
                        ModelStatus.Error -> PulseColors.Error
                    },
                )
            },
        )
        StatusSeparator()
        MonoLabel(modelName)
        StatusSeparator()
        StatusItem(text = sync, color = PulseColors.FgDim)
        StatusSeparator()
        StatusItem(text = String.format("%.1f MB / %d MB", usedMb, totalMb.toInt()))
        Spacer(Modifier.weight(1f))
        StatusItem(text = "English")
        StatusSeparator()
        StatusItem(text = "Dark")
        StatusSeparator()
        StatusItem(text = "UTF-8")
        StatusSeparator()
        StatusItem(text = cursor)
    }
}

enum class ModelStatus { Ready, Loading, Error }

/* ============================================================ PREVIEW DATA */

data class SidebarChat(
    val id: String,
    val title: String,
    val preview: String,
    val section: String,
    val pinned: Boolean = false,
)

private fun previewChats(): List<SidebarChat> = listOf(
    SidebarChat("welcome", "Welcome to Pulse", "Local-first AI side panel", "Today"),
    SidebarChat("plan", "Sprint plan", "What should ship this week?", "Today", pinned = true),
    SidebarChat("refactor", "Refactor: room schema", "Migrate to FTS5", "Today"),
    SidebarChat("ui-bug", "UI bug: dark mode flash", "Splash screen regression", "Yesterday"),
    SidebarChat("notes", "Notes: open questions", "Sync semantics", "Yesterday"),
    SidebarChat("demo", "Demo script", "Walkthrough for the team", "Last week"),
)








