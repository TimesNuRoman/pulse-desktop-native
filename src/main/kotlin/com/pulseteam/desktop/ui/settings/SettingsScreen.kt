// SPDX-License-Identifier: Apache-2.0
// Pulse — Settings screen (VSCode-style: ActivityRail + Nav + panel). Per spec 6.x.
// All edges rectangular. Tokyo Night palette.
package com.pulseteam.desktop.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseteam.desktop.ui.common.HDivider
import com.pulseteam.desktop.ui.common.Kbd
import com.pulseteam.desktop.data.ai.ActiveDownload
import com.pulseteam.desktop.data.ai.DownloadState
import com.pulseteam.desktop.data.ai.ModelEntry
import com.pulseteam.desktop.data.ai.ModelsRepository
import com.pulseteam.desktop.data.ai.RuntimeDownloader
import com.pulseteam.desktop.data.ai.RuntimeState
import com.pulseteam.desktop.ui.common.MonoLabel
import com.pulseteam.desktop.ui.common.StatusDot
import com.pulseteam.desktop.data.settings.AppSettings
import com.pulseteam.desktop.data.settings.AppSettingsStore
import com.pulseteam.desktop.data.settings.RoutingMode
import com.pulseteam.desktop.ui.theme.MonoStyle
import com.pulseteam.desktop.ui.theme.PulseColors

private enum class SettingsTab(val section: String) {
    Account("account"),
    Models("models"),
    Routing("routing"),
    Inference("inference"),
    Hotkeys("hotkeys")
}

private enum class SettingsSection(val title: String, val icon: ImageVector, val tab: SettingsTab) {
    Account("Account", Icons.Default.Person, SettingsTab.Models),
    Models("Models", Icons.Default.Memory, SettingsTab.Models),
    Routing("Routing", Icons.Default.Tune, SettingsTab.Routing),
    Inference("Inference", Icons.Default.Tune, SettingsTab.Inference),
    Hotkeys("Hotkeys", Icons.Default.GraphicEq, SettingsTab.Hotkeys),
}

@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    userEmail: String? = null,
    onSignOut: () -> Unit = {},
    onUnlockSync: () -> Unit = {},
    modelsRepo: ModelsRepository? = null,
    runtimeDownloader: RuntimeDownloader? = null,
) {
    var activeTab by remember { mutableStateOf(if (userEmail != null) SettingsTab.Account else SettingsTab.Models) }
    var dirty by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PulseColors.Bg.copy(alpha = 0.6f))
            .clickable { /* backdrop only */  },
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
                Text("Settings", color = PulseColors.FgBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(PulseColors.Bg3, RectangleShape)
                        .border(1.dp, PulseColors.Border, RectangleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clickable { onDismiss()  },
                ) {
                    Text("Close", color = PulseColors.Fg, fontSize = 12.sp)
                }
            }

            // Body
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ActivityRail(modifier = Modifier.width(56.dp).fillMaxHeight())
                SettingsNav(
                    active = activeTab,
                    onTabChange = { activeTab = it },
                    modifier = Modifier.width(240.dp).fillMaxHeight(),
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (activeTab) {
                        SettingsTab.Account -> AccountPanel(
                            userEmail = userEmail,
                            onSignOut = onSignOut,
                            onUnlockSync = onUnlockSync,
                        )
                        SettingsTab.Models -> ModelsPanel(
                            onChange = { dirty = true },
                            modelsRepo = modelsRepo,
                            runtimeDownloader = runtimeDownloader,
                        )
                        SettingsTab.Routing -> RoutingPanel(onChange = { dirty = true })
                        SettingsTab.Inference -> InferencePanel(onChange = { dirty = true })
                        SettingsTab.Hotkeys -> HotkeysPanel()
                    }
                }
            }

            // Save bar
            if (dirty) {
                HDivider()
                SaveBar(onReset = { dirty = false }, onSave = { dirty = false }, onDiscard = { dirty = false })
            }
        }
    }
}

@Composable
private fun ActivityRail(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(PulseColors.Bg)
            .border(width = 1.dp, color = PulseColors.Border, shape = RectangleShape)
            .padding(vertical = 8.dp),
    ) {
        val items = listOf(
            Icons.Default.Person to "Account",
            Icons.Default.Memory to "Models",
            Icons.Default.Tune to "Routing",
            Icons.Default.Tune to "Inference",
            Icons.Default.GraphicEq to "Hotkeys",
        )
        items.forEach { entry ->
            val icon = entry.first
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .padding(4.dp)
                    .background(PulseColors.Bg3, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = PulseColors.FgDim, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.weight(1f))
        // Avatar at the bottom
        Box(
            modifier = Modifier
                .size(32.dp)
                .padding(4.dp)
                .background(PulseColors.Accent2, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("R", color = PulseColors.Bg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SettingsNav(
    active: SettingsTab,
    onTabChange: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .background(PulseColors.Bg2)
            .border(width = 1.dp, color = PulseColors.Border, shape = RectangleShape)
            .padding(vertical = 8.dp),
    ) {
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
            Text(if (filter.isEmpty()) "Search settings\u2026" else filter, color = if (filter.isEmpty()) PulseColors.FgDim else PulseColors.Fg, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        SettingsSection.entries
            .filter { active.section == it.tab.section }
            .filter { filter.isBlank() || it.title.lowercase().contains(filter.lowercase()) }
            .forEach { section ->
                val isActive = filter.isBlank() && section == SettingsSection.entries.first { it.tab.section == active.section }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isActive) PulseColors.AccentSoft else PulseColors.Bg2, RectangleShape)
                        .border(width = if (isActive) 2.dp else 0.dp, color = if (isActive) PulseColors.Accent else PulseColors.Bg2, shape = RectangleShape)
                        .clickable { onTabChange(section.tab)  }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(section.title, color = if (isActive) PulseColors.Accent else PulseColors.Fg, fontSize = 13.sp)
                }
            }
    }
}

@Composable
private fun AccountPanel(userEmail: String?, onSignOut: () -> Unit, onUnlockSync: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Account", color = PulseColors.FgBright, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Your signed-in identity and sync key.", color = PulseColors.FgDim, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))

        // Identity card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PulseColors.Bg2, RectangleShape)
                .border(1.dp, PulseColors.Border, RectangleShape)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(40.dp)
                    .background(PulseColors.Accent2, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (userEmail?.firstOrNull()?.uppercase() ?: "?").toString(),
                    color = PulseColors.Bg, fontSize = 16.sp, fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(userEmail ?: "Not signed in", color = PulseColors.Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("E2E key derived from your password via scrypt(N=2^15, r=8, p=1).", color = PulseColors.FgDim, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        // Sync status card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PulseColors.Bg2, RectangleShape)
                .border(1.dp, PulseColors.Border, RectangleShape)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(8.dp)
                    .background(PulseColors.Green, RectangleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Sync", color = PulseColors.Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text("Encrypted end-to-end. The server only sees ciphertext + nonce + tag.", color = PulseColors.FgDim, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        // Sign out
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PulseColors.Bg3, RectangleShape)
                .border(1.dp, PulseColors.Border, RectangleShape)
                .clickable { onSignOut() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⎋", color = PulseColors.FgDim, fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
            Text("Sign out", color = PulseColors.Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(8.dp))

        // Re-unlock (forces PasswordDialog to re-appear next frame)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PulseColors.Bg3, RectangleShape)
                .border(1.dp, PulseColors.Border, RectangleShape)
                .clickable { onUnlockSync() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚷", color = PulseColors.FgDim, fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
            Text("Re-unlock sync key", color = PulseColors.Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ModelsPanel(
    onChange: () -> Unit,
    modelsRepo: ModelsRepository? = null,
    runtimeDownloader: RuntimeDownloader? = null,
) {
    val settings by AppSettingsStore.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Runtime status (compact)
        if (runtimeDownloader != null) {
            RuntimeStatusRow(runtimeDownloader)
        }

        Text("Active model", color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))

        if (modelsRepo == null) {
            // Fallback: legacy hardcoded list (only if ModelsRepository wasn't passed in)
            Text(
                "Models catalog not loaded yet. Restart Pulse to see real state.",
                color = PulseColors.FgDim,
                fontSize = 11.sp,
            )
            return
        }

        val entries by modelsRepo.entries().collectAsState()
        val serverState by modelsRepo.server().collectAsState()

        // List models in 2 columns
        entries.chunked(2).forEach { rowEntries ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowEntries.forEach { entry ->
                    ModelEntryCard(
                        entry = entry,
                        isActive = entry.meta.id == settings.activeModelId,
                        isServerReady = serverState.status == com.pulseteam.desktop.data.ai.ServerStatus.Ready
                            && entry.meta.id == serverState.modelId,
                        modelsRepo = modelsRepo,
                        onMakeActive = {
                            AppSettingsStore.update { it.copy(activeModelId = entry.meta.id) }
                            onChange()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowEntries.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RuntimeStatusRow(runtime: RuntimeDownloader) {
    val progress by runtime.progress().collectAsState()
    val installed = runtime.isInstalled()

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        StatusDot(
            color = when {
                installed -> PulseColors.Green
                progress.state == RuntimeState.Failed -> PulseColors.Error
                progress.state == RuntimeState.Downloading || progress.state == RuntimeState.Extracting -> PulseColors.Warn
                else -> PulseColors.FgDim
            },
            size = 8.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                installed -> "AI runtime ready"
                progress.state == RuntimeState.Downloading -> "Downloading AI runtime… ${(progress.fraction * 100).toInt()}%"
                progress.state == RuntimeState.Extracting -> "Extracting AI runtime…"
                progress.state == RuntimeState.Failed -> "Runtime download failed: ${progress.error ?: "?"}"
                else -> "AI runtime not installed"
            },
            color = if (installed) PulseColors.Green else PulseColors.Fg,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        if (!installed && progress.state != RuntimeState.Downloading && progress.state != RuntimeState.Extracting) {
            Box(
                modifier = Modifier
                    .background(PulseColors.Bg3, RectangleShape)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .clickable { runtime.startDownload() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("Install", color = PulseColors.Fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (progress.state == RuntimeState.Downloading) {
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(PulseColors.Bg3, RectangleShape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.fraction.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(PulseColors.Warn, RectangleShape),
            )
        }
    }
}

@Composable
private fun ModelEntryCard(
    entry: ModelEntry,
    isActive: Boolean,
    isServerReady: Boolean,
    modelsRepo: ModelsRepository,
    onMakeActive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeDownload by remember { mutableStateOf<ActiveDownload?>(null) }
    val idleState = remember { kotlinx.coroutines.flow.MutableStateFlow(DownloadState.Idle) }
    val idleProgress = remember { kotlinx.coroutines.flow.MutableStateFlow(com.pulseteam.desktop.data.ai.DownloadProgress(entry.meta.id, 0, 0, 0.0, 0)) }
    val downloadState = (activeDownload?.state ?: idleState).collectAsState().value
    val progress = (activeDownload?.progress ?: idleProgress).collectAsState().value

    val border = when {
        isServerReady -> PulseColors.Green
        isActive && entry.installed -> PulseColors.Accent
        entry.installed -> PulseColors.Border
        downloadState == DownloadState.Downloading -> PulseColors.Warn
        downloadState == DownloadState.Failed -> PulseColors.Error
        else -> PulseColors.Border
    }
    val bg = when {
        isServerReady -> PulseColors.BgInput
        isActive && entry.installed -> PulseColors.BgInput
        else -> PulseColors.Bg2
    }

    Column(
        modifier = modifier
            .border(1.dp, border, RectangleShape)
            .background(bg, RectangleShape)
            .clickable(enabled = entry.installed) { onMakeActive() }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(entry.meta.displayName, color = if (isServerReady) PulseColors.Green else if (isActive) PulseColors.Accent else PulseColors.Fg)
            Spacer(Modifier.weight(1f))
            when {
                isServerReady -> Text("●", color = PulseColors.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                isActive -> Text("✓", color = PulseColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                else -> {}
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${entry.meta.quant} · ${entry.meta.sizeBytes / 1_000_000} MB · RAM ${entry.meta.minRamGb} GB",
            color = PulseColors.FgDim,
            fontSize = 10.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                isServerReady -> {
                    StatusDot(PulseColors.Green, size = 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("running", color = PulseColors.Green, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                entry.installed -> {
                    StatusDot(PulseColors.Green, size = 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("installed · click to activate", color = PulseColors.FgDim, fontSize = 10.sp)
                }
                downloadState == DownloadState.Downloading -> {
                    StatusDot(PulseColors.Warn, size = 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "downloading ${(progress.fraction * 100).toInt()}% · ${progress.speedMBps.toInt()} MB/s",
                        color = PulseColors.Warn,
                        fontSize = 10.sp,
                    )
                }
                downloadState == DownloadState.Failed -> {
                    Box(
                        modifier = Modifier
                            .background(PulseColors.Error, RectangleShape)
                            .clickable { activeDownload = modelsRepo.startDownload(entry.meta) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Retry", color = PulseColors.Bg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .background(PulseColors.Accent, RectangleShape)
                            .clickable { activeDownload = modelsRepo.startDownload(entry.meta) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("Download", color = PulseColors.Bg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        // Progress bar
        if (downloadState == DownloadState.Downloading) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(PulseColors.Bg3, RectangleShape),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.fraction.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(PulseColors.Warn, RectangleShape),
                )
            }
        }
        // Delete button for installed models (if not active)
        if (entry.installed && !isActive) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .background(PulseColors.Bg3, RectangleShape)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .clickable { modelsRepo.deleteInstalled(entry.meta) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("Delete", color = PulseColors.FgDim, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun RoutingPanel(onChange: () -> Unit) {
    val settings by AppSettingsStore.state.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RoutingOption(
            label = "Local only",
            desc = "All inference runs on this device. No network.",
            mode = RoutingMode.LocalOnly,
            current = settings.routing,
            onClick = { AppSettingsStore.update { it.copy(routing = RoutingMode.LocalOnly) }; onChange() },
        )
        RoutingOption(
            label = "API only",
            desc = "All inference routed through api.ownlocalml.com.",
            mode = RoutingMode.ApiOnly,
            current = settings.routing,
            onClick = { AppSettingsStore.update { it.copy(routing = RoutingMode.ApiOnly) }; onChange() },
        )
        RoutingOption(
            label = "Hybrid",
            desc = "Local by default; large-context prompts fall back to API.",
            mode = RoutingMode.Hybrid,
            current = settings.routing,
            onClick = { AppSettingsStore.update { it.copy(routing = RoutingMode.Hybrid) }; onChange() },
        )
    }
}

@Composable
private fun RoutingOption(
    label: String,
    desc: String,
    mode: RoutingMode,
    current: RoutingMode,
    onClick: () -> Unit,
) {
    val selected = current == mode
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) PulseColors.Accent else PulseColors.Border, RectangleShape)
            .background(if (selected) PulseColors.BgInput else PulseColors.Bg2, RectangleShape)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (selected) PulseColors.Accent else PulseColors.FgDim, size = 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = if (selected) PulseColors.Accent else PulseColors.Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = PulseColors.FgDim, fontSize = 11.sp, lineHeight = 14.sp)
        }
        if (selected) {
            Text("\u2713", color = PulseColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InferencePanel(onChange: () -> Unit) {
    val settings by AppSettingsStore.state.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Temperature slider
        Text("Temperature: ${"%.2f".format(settings.temperature)}", color = PulseColors.Fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = settings.temperature.toFloat(),
            onValueChange = { v -> AppSettingsStore.update { it.copy(temperature = v.toDouble()) }; onChange() },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = PulseColors.Accent,
                activeTrackColor = PulseColors.Accent,
                inactiveTrackColor = PulseColors.Bg3,
            ),
        )
        // Top-p slider
        Text("Top-p: ${"%.2f".format(settings.topP)}", color = PulseColors.Fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = settings.topP.toFloat(),
            onValueChange = { v -> AppSettingsStore.update { it.copy(topP = v.toDouble()) }; onChange() },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = PulseColors.Accent,
                activeTrackColor = PulseColors.Accent,
                inactiveTrackColor = PulseColors.Bg3,
            ),
        )
        // Max tokens
        Text("Max tokens: ${settings.maxTokens}", color = PulseColors.Fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = settings.maxTokens.toFloat(),
            onValueChange = { v ->
                // Round to nearest 64
                val n = (v / 64).toInt() * 64
                AppSettingsStore.update { it.copy(maxTokens = n.coerceIn(64, 8192)) }
                onChange()
            },
            valueRange = 64f..8192f,
            colors = SliderDefaults.colors(
                thumbColor = PulseColors.Accent,
                activeTrackColor = PulseColors.Accent,
                inactiveTrackColor = PulseColors.Bg3,
            ),
        )
    }
}

@Composable
private fun HotkeysPanel() {
    val hotkeys = listOf(
        "Ctrl N" to "New chat",
        "Ctrl Shift N" to "New note",
        "Ctrl K" to "Open command palette",
        "Ctrl S" to "Sync now",
        "Ctrl ," to "Open settings",
        "Ctrl B" to "Toggle sidebar",
        "Ctrl J" to "Toggle right panel",
        "Ctrl Shift M" to "Switch model",
        "Ctrl Shift V" to "Toggle voice",
        "Space (hold)" to "Push-to-talk",
    )
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(hotkeys) { (key, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kbd(key)
                Spacer(Modifier.width(12.dp))
                Text(label, color = PulseColors.Fg, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SelectRow(label: String, value: String, onChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, PulseColors.Border, RectangleShape)
            .background(PulseColors.Bg2, RectangleShape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable { onChange()  },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PulseColors.Fg, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = PulseColors.FgDim, fontSize = 12.sp, style = MonoStyle)
        Spacer(Modifier.width(4.dp))
        Text("▾", color = PulseColors.FgDim, fontSize = 12.sp)
    }
}

@Composable
private fun ToggleRow(label: String, initial: Boolean, onChange: () -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, PulseColors.Border, RectangleShape)
            .background(PulseColors.Bg2, RectangleShape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PulseColors.Fg, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { checked = it; onChange() },
        )
    }
}

@Composable
private fun SaveBar(onReset: () -> Unit, onSave: () -> Unit, onDiscard: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(PulseColors.Bg)
            .border(width = 1.dp, color = PulseColors.Border, shape = RectangleShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Unsaved changes", color = PulseColors.Warn, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        BarButton("Reset", onClick = onReset)
        Spacer(Modifier.width(8.dp))
        BarButton("Discard", onClick = onDiscard)
        Spacer(Modifier.width(8.dp))
        BarButton("Save", onClick = onSave, primary = true)
    }
}

@Composable
private fun BarButton(text: String, onClick: () -> Unit, primary: Boolean = false) {
    val bg = if (primary) PulseColors.Accent else PulseColors.Bg2
    val fg = if (primary) PulseColors.Bg else PulseColors.Fg
    Box(
        modifier = Modifier
            .background(bg, RectangleShape)
            .border(1.dp, PulseColors.Border, RectangleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { onClick()  },
    ) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium)
    }
}







