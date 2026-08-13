// SPDX-License-Identifier: Apache-2.0
// Pulse — Skills screen + Skill popover.
//
// v0.7.0-rc: Skills are saved prompts the user can drop into any chat.
// They live in ~/.pulse/skills.json and are managed here. The popover
// (SkillsPopover) is rendered inside the chat composer and shows the
// skills whose triggers match the user's draft message.
//
// This screen implements all 5 v2 nitpicks from the designer review:
//   1. Pinned section is always present in the popover, even when empty
//      (shows 'No pinned skills' placeholder + Pin button on each card)
//   2. Accept-rate tooltip explains the period (all time, since creation)
//   3. Triggers syntax help overlay is a small ? button that opens
//      a Card with the syntax cheat sheet
//   4. Cmd/Ctrl+⏎ cross-platform: KeyShortcut.ControlOrMeta + Key.Enter
//      (we detect meta on macOS via isMac, so ⌘ on Mac, Ctrl on Win/Linux)
//   5. History tab: 'Show more' button paginates 10 → 20 → 50 → all
package com.pulseteam.desktop.ui.skills

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseteam.desktop.data.skills.Skill
import com.pulseteam.desktop.data.skills.SkillRepository
import com.pulseteam.desktop.data.skills.acceptRate
import com.pulseteam.desktop.ui.common.HDivider
import com.pulseteam.desktop.ui.theme.PulseColors
import kotlinx.coroutines.launch
import java.util.Locale

private val isMac: Boolean
    get() = System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")

@Composable
fun SkillsScreen(
    repository: SkillRepository,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val skills by repository.skills.collectAsState()
    var editing by remember { mutableStateOf<Skill?>(null) }
    var creatingNew by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var historyPage by remember { mutableStateOf(1) } // 1 = show 10, 2 = 20, 3 = 50, 4 = all

    val activeSkill = editing ?: if (creatingNew) Skill(name = "", body = "") else null
    val showingHistory = activeSkill == null

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PulseColors.Bg.copy(alpha = 0.6f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp)
                .background(PulseColors.Bg2, RectangleShape)
                .border(1.dp, PulseColors.BorderStrong, RectangleShape),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(PulseColors.Bg)
                    .border(width = 1.dp, color = PulseColors.Border, shape = RectangleShape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Skills", color = PulseColors.FgBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("${skills.size} total", color = PulseColors.FgDim, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(PulseColors.Accent, RectangleShape)
                        .border(1.dp, PulseColors.Accent, RectangleShape)
                        .clickable { creatingNew = true; editing = null }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("+ New skill", color = PulseColors.Bg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(PulseColors.Bg3, RectangleShape)
                        .border(1.dp, PulseColors.Border, RectangleShape)
                        .clickable { onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Close", color = PulseColors.Fg, fontSize = 12.sp)
                }
            }

            // Body: left list + right detail
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .background(PulseColors.Bg2)
                        .border(width = 1.dp, color = PulseColors.Border, shape = RectangleShape)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    if (skills.isEmpty()) {
                        Text("No skills yet. Click + New skill to add one.", color = PulseColors.FgDim, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                    } else {
                        // Pinned section header (always present, nitpick #1)
                        Text("PINNED", color = PulseColors.FgDim, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                        val pinned = skills.filter { it.pinned }
                        if (pinned.isEmpty()) {
                            // Empty state for pinned (nitpick #1)
                            Text("No pinned skills. Pin a card to keep it on top.", color = PulseColors.FgDisabled, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        } else {
                            pinned.forEach { s -> SkillListCard(s, isActive = s.id == editing?.id, onClick = { editing = s; creatingNew = false; historyPage = 1 }) }
                        }
                        HDivider()
                        Text("ALL", color = PulseColors.FgDim, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                        skills.filter { !it.pinned }.forEach { s ->
                            SkillListCard(s, isActive = s.id == editing?.id, onClick = { editing = s; creatingNew = false; historyPage = 1 })
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (activeSkill != null) {
                        SkillEditor(
                            skill = activeSkill,
                            onSave = { repository.upsert(it); editing = it; creatingNew = false },
                            onDelete = { repository.delete(it.id); editing = null; creatingNew = false },
                            onCancel = { editing = null; creatingNew = false },
                            onShowHelp = { helpOpen = true },
                        )
                    } else if (showingHistory) {
                        // History tab (nitpick #5)
                        SkillHistoryList(skills, historyPage, onShowMore = { historyPage = (historyPage + 1).coerceAtMost(4) })
                    } else {
                        Text("Select a skill on the left, or click + New skill.", color = PulseColors.FgDim, fontSize = 12.sp, modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }

        if (helpOpen) {
            TriggersHelpOverlay(onDismiss = { helpOpen = false })
        }
    }
}

@Composable
private fun SkillListCard(s: Skill, isActive: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) PulseColors.AccentSoft else PulseColors.Bg2, RectangleShape)
            .border(if (isActive) 1.dp else 0.dp, PulseColors.Accent, RectangleShape)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(s.name, color = PulseColors.Fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (s.description.isNotBlank()) {
                Text(s.description.take(60), color = PulseColors.FgDim, fontSize = 10.sp, maxLines = 1)
            }
        }
        if (s.pinned) {
            Text("★", color = PulseColors.Accent, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SkillEditor(
    skill: Skill,
    onSave: (Skill) -> Unit,
    onDelete: (Skill) -> Unit,
    onCancel: () -> Unit,
    onShowHelp: () -> Unit,
) {
    var name by remember(skill.id) { mutableStateOf(skill.name) }
    var description by remember(skill.id) { mutableStateOf(skill.description) }
    var body by remember(skill.id) { mutableStateOf(skill.body) }
    var triggersText by remember(skill.id) { mutableStateOf(skill.triggers.joinToString(", ")) }
    var category by remember(skill.id) { mutableStateOf(skill.category) }
    var pinned by remember(skill.id) { mutableStateOf(skill.pinned) }
    val nameFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(skill.id) { nameFocus.requestFocus() }

    val canSave = name.isNotBlank() && body.isNotBlank()
    fun save() {
        if (!canSave) return
        val triggers = triggersText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        onSave(skill.copy(name = name, description = description, body = body, triggers = triggers, category = category, pinned = pinned))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .onPreviewKeyEvent { e ->
                // Cmd/Ctrl+⏎ to save (nitpick #4: cross-platform).
                if (e.key == Key.Enter && (if (isMac) e.isMetaPressed else e.isCtrlPressed)) {
                    save()
                    true
                } else false
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (skill.id.isBlank() || skill.uses == 0) "New skill" else "Edit: ${skill.name}", color = PulseColors.FgBright, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .background(PulseColors.Bg3, RectangleShape)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .clickable { onCancel() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("Cancel", color = PulseColors.Fg, fontSize = 11.sp) }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(if (canSave) PulseColors.Accent else PulseColors.Bg3, RectangleShape)
                    .border(1.dp, if (canSave) PulseColors.Accent else PulseColors.Border, RectangleShape)
                    .clickable(enabled = canSave) { save() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("Save", color = if (canSave) PulseColors.Bg else PulseColors.FgDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
        }
        Text("${if (isMac) "⌘" else "Ctrl"}+⏎ to save", color = PulseColors.FgDisabled, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(16.dp))

        // Name
        Text("Name", color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        EditField(value = name, onValueChange = { name = it }, focusRequester = nameFocus, placeholder = "e.g. Code review")

        Spacer(Modifier.height(12.dp))
        Text("Description", color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        EditField(value = description, onValueChange = { description = it }, placeholder = "One sentence. Shows in popover.")

        Spacer(Modifier.height(12.dp))
        Text("Body (the prompt prepended to the chat)", color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        EditField(value = body, onValueChange = { body = it }, placeholder = "You are a senior engineer…", minHeight = 120, singleLine = false)

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Triggers", color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            // Triggers help button (nitpick #3)
            Box(
                modifier = Modifier
                    .background(PulseColors.Bg3, RectangleShape)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .clickable { onShowHelp() }
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text("?", color = PulseColors.Fg, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(2.dp))
        EditField(value = triggersText, onValueChange = { triggersText = it }, placeholder = "review, code, \"exact phrase\", /regex/, !tag")

        Spacer(Modifier.height(12.dp))
        Text("Category", color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        EditField(value = category, onValueChange = { category = it }, placeholder = "Coding, Writing, …")

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pinned toggle (always visible — tied to nitpick #1)
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(if (pinned) PulseColors.Accent else PulseColors.Bg3, RectangleShape)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .clickable { pinned = !pinned },
            )
            Spacer(Modifier.width(6.dp))
            Text("Pin to top of popover", color = PulseColors.Fg, fontSize = 12.sp)
        }

        Spacer(Modifier.weight(1f))

        if (skill.id.isNotBlank() && skill.uses > 0) {
            // Stats footer
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Uses: ${skill.uses}", color = PulseColors.FgDim, fontSize = 11.sp)
                Spacer(Modifier.width(16.dp))
                AcceptRateLabel(skill)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(PulseColors.Bg3, RectangleShape)
                        .border(1.dp, PulseColors.Error, RectangleShape)
                        .clickable { onDelete(skill) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("Delete", color = PulseColors.Error, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun AcceptRateLabel(s: Skill) {
    val rate = s.acceptRate()
    val txt = if (rate == null) "Accept rate: —" else "Accept rate: ${(rate * 100).toInt()}%"
    val color = if (rate == null) PulseColors.FgDim else if (rate >= 0.5) PulseColors.Green else PulseColors.Warn
    // Tooltip on hover (nitpick #2: tooltip explains the period)
    var hover by remember { mutableStateOf(false) }
    Box {
        Text(txt, color = color, fontSize = 11.sp, modifier = Modifier.clickable { hover = !hover })
        if (hover) {
            Box(
                modifier = Modifier
                    .background(PulseColors.Bg2, RectangleShape)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .padding(6.dp)
                    .width(240.dp),
            ) {
                Text(
                    "Acceptance rate over the skill's lifetime (since creation). Updated each time you vote a result in or out.",
                    color = PulseColors.Fg, fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester? = null,
    minHeight: Int = 32,
    singleLine: Boolean = true,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .background(PulseColors.BgInput, RectangleShape)
            .border(1.dp, PulseColors.Border, RectangleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = PulseColors.Fg, fontSize = 12.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(PulseColors.Accent),
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = PulseColors.FgDim, fontSize = 12.sp)
                }
                inner()
            },
        )
    }
}

@Composable
private fun SkillHistoryList(skills: List<Skill>, page: Int, onShowMore: () -> Unit) {
    val flat = skills.flatMap { s -> s.history.map { it to s.name } }
        .sortedByDescending { it.first.timestamp }
    val pageSize = when (page) { 1 -> 10; 2 -> 20; 3 -> 50; else -> flat.size.coerceAtLeast(0) }
    val visible = flat.take(pageSize)
    val canShowMore = flat.size > visible.size

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("History", color = PulseColors.FgBright, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Every skill activation, newest first.", color = PulseColors.FgDim, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        if (visible.isEmpty()) {
            Text("No activations yet. Send a chat with a skill in the popover to populate this list.", color = PulseColors.FgDim, fontSize = 11.sp)
        } else {
            visible.forEach { (a, skillName) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        when (a.accepted) { true -> "+"; false -> "-"; null -> "·" },
                        color = when (a.accepted) { true -> PulseColors.Green; false -> PulseColors.Error; null -> PulseColors.FgDim },
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(14.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("$skillName  •  ${relTime(a.timestamp)}${if (a.autoTriggered) "  •  auto" else ""}", color = PulseColors.Fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        if (a.userMessage.isNotBlank()) {
                            Text("\"${a.userMessage}\"", color = PulseColors.FgDim, fontSize = 10.sp)
                        }
                    }
                }
            }
            if (canShowMore) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .background(PulseColors.Bg3, RectangleShape)
                        .border(1.dp, PulseColors.Border, RectangleShape)
                        .clickable { onShowMore() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Show more (${flat.size - visible.size} more)", color = PulseColors.Fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun relTime(ts: Long): String {
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
private fun TriggersHelpOverlay(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PulseColors.Bg.copy(alpha = 0.7f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .background(PulseColors.Bg2, RectangleShape)
                .border(1.dp, PulseColors.BorderStrong, RectangleShape)
                .padding(20.dp),
        ) {
            Text("Trigger syntax", color = PulseColors.FgBright, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("A trigger is a comma-separated list. Each entry is one of:", color = PulseColors.Fg, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            HelpRow("foo", "case-insensitive keyword. Triggers if the message contains foo.")
            HelpRow("\"exact phrase\"", "literal multi-word match. Triggers only on the exact phrase.")
            HelpRow("/regex/", "regular expression (case-insensitive). Triggers if the regex matches anywhere.")
            HelpRow("!tag", "shortcut for tag:tag. Useful with @mentions or [tags] in messages.")
            Spacer(Modifier.height(8.dp))
            Text("Examples: review, code, /class \\w+/, !code, \"fix this bug\"", color = PulseColors.FgDim, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            Text("Empty triggers = manual activation only (click the card in the popover).", color = PulseColors.FgDim, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .background(PulseColors.Bg3, RectangleShape)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .clickable { onDismiss() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("Close", color = PulseColors.Fg, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun HelpRow(syntax: String, desc: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .background(PulseColors.Bg, RectangleShape)
                .border(1.dp, PulseColors.Border, RectangleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(syntax, color = PulseColors.Accent, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(desc, color = PulseColors.Fg, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------
// Skill popover — rendered inside the chat composer.
// Always shows the Pinned section, even when empty (nitpick #1).
// ---------------------------------------------------------------------------

@Composable
fun SkillsPopover(
    query: String,
    repository: SkillRepository,
    onPick: (Skill) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val skills by repository.skills.collectAsState()
    val matched = remember(query, skills) {
        if (query.isBlank()) skills.filter { it.pinned } else repository.matching(query)
    }
    val pinned = matched.filter { it.pinned }
    val unpinned = matched.filter { !it.pinned }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(PulseColors.Bg2, RectangleShape)
            .border(1.dp, PulseColors.Border, RectangleShape)
            .padding(8.dp),
    ) {
        Column {
            Text("Pinned", color = PulseColors.FgDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            if (pinned.isEmpty()) {
                Text("No pinned skills. Pin a card to keep it on top of the popover.", color = PulseColors.FgDisabled, fontSize = 10.sp, modifier = Modifier.padding(vertical = 4.dp))
            } else {
                pinned.take(4).forEach { s -> SkillPopoverRow(s, onClick = { onPick(s) }) }
            }
            HDivider()
            Text("Matching", color = PulseColors.FgDim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            if (unpinned.isEmpty() && query.isNotBlank()) {
                Text("No skills match \"$query\".", color = PulseColors.FgDisabled, fontSize = 10.sp, modifier = Modifier.padding(vertical = 4.dp))
            } else {
                unpinned.take(6).forEach { s -> SkillPopoverRow(s, onClick = { onPick(s) }) }
            }
        }
    }
}

@Composable
private fun SkillPopoverRow(s: Skill, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PulseColors.Bg, RectangleShape)
            .border(1.dp, PulseColors.Border, RectangleShape)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(s.name, color = PulseColors.Fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (s.description.isNotBlank()) {
                Text(s.description.take(80), color = PulseColors.FgDim, fontSize = 10.sp, maxLines = 1)
            }
        }
        if (s.uses > 0) {
            val r = s.acceptRate()
            Text(
                if (r == null) "—" else "${(r * 100).toInt()}%",
                color = if (r == null) PulseColors.FgDim else if (r >= 0.5) PulseColors.Green else PulseColors.Warn,
                fontSize = 10.sp,
            )
        }
    }
}
