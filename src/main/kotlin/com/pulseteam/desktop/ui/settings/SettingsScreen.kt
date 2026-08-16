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
import com.pulseteam.desktop.data.desktop.DesktopController
import com.pulseteam.desktop.data.desktop.SafetyLevel
import com.pulseteam.desktop.data.voice.WhisperTranscriber
import kotlinx.coroutines.launch
import com.pulseteam.desktop.data.ai.ModelsRepository
import com.pulseteam.desktop.data.ai.RuntimeDownloader
import com.pulseteam.desktop.data.ai.RuntimeState
import com.pulseteam.desktop.ui.common.MonoLabel
import com.pulseteam.desktop.ui.common.StatusDot
import com.pulseteam.desktop.data.settings.AppSettings
import com.pulseteam.desktop.data.settings.AppSettingsStore
import com.pulseteam.desktop.data.settings.RoutingMode
import com.pulseteam.desktop.data.settings.VisionModel
import com.pulseteam.desktop.ui.theme.MonoStyle
import com.pulseteam.desktop.ui.theme.PulseColors

private enum class SettingsTab(val section: String) {
    Account("account"),
    Models("models"),
    Routing("routing"),
    Inference("inference"),
    Hotkeys("hotkeys"),
    Desktop("desktop"),
}

private enum class SettingsSection(val title: String, val icon: ImageVector, val tab: SettingsTab) {
    Account("Account", Icons.Default.Person, SettingsTab.Models),
    Models("Models", Icons.Default.Memory, SettingsTab.Models),
    Routing("Routing", Icons.Default.Tune, SettingsTab.Routing),
    Inference("Inference", Icons.Default.Tune, SettingsTab.Inference),
    Hotkeys("Hotkeys", Icons.Default.GraphicEq, SettingsTab.Hotkeys),
    Desktop("Desktop", Icons.Default.Memory, SettingsTab.Desktop),
}

@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    userEmail: String? = null,
    onSignOut: () -> Unit = {},
    onUnlockSync: () -> Unit = {},
    modelsRepo: ModelsRepository,
    runtimeDownloader: RuntimeDownloader? = null,
    whisper: WhisperTranscriber? = null,
    desktop: DesktopController? = null,
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
                            whisper = whisper,
                        )
                        SettingsTab.Routing -> RoutingPanel(onChange = { dirty = true })
                        SettingsTab.Inference -> InferencePanel(onChange = { dirty = true })
                        SettingsTab.Hotkeys -> HotkeysPanel()
                        SettingsTab.Desktop -> DesktopPanel(
                            onChange = { dirty = true },
                            desktop = desktop,
                        )
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
            Icons.Default.Memory to "Desktop",
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
    modelsRepo: ModelsRepository,
    runtimeDownloader: RuntimeDownloader? = null,
    whisper: WhisperTranscriber? = null,
) {
    val settings by AppSettingsStore.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Runtime status (compact)
        if (runtimeDownloader != null) {
            RuntimeStatusRow(runtimeDownloader)
        }

        // Voice / Whisper status (compact, in same column as runtime so
        // the user sees "llama + whisper" both at a glance).
        if (whisper != null) {
            WhisperStatusRow(whisper)
        }

        Text("Active model", color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))

        // modelsRepo is always wired from Main.kt; this panel would not
        // render without it, so the previous "catalog not loaded" stub
        // branch is dead code.

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
private fun WhisperStatusRow(whisper: WhisperTranscriber) {
    val state by whisper.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val isWorking = state.phase != com.pulseteam.desktop.data.voice.WhisperPhase.Idle

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        StatusDot(
            color = when {
                state.isReady -> PulseColors.Green
                state.error != null -> PulseColors.Error
                isWorking -> PulseColors.Warn
                else -> PulseColors.FgDim
            },
            size = 8.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                state.isReady -> "Voice (whisper) ready"
                state.error != null -> "Whisper error: ${state.error}"
                state.phase == com.pulseteam.desktop.data.voice.WhisperPhase.DownloadingBinary ->
                    "Downloading whisper.cpp… ${(state.fraction * 100).toInt()}%"
                state.phase == com.pulseteam.desktop.data.voice.WhisperPhase.ExtractingBinary ->
                    "Extracting whisper.cpp…"
                state.phase == com.pulseteam.desktop.data.voice.WhisperPhase.DownloadingModel ->
                    "Downloading whisper model (75 MB)… ${(state.fraction * 100).toInt()}%"
                state.phase == com.pulseteam.desktop.data.voice.WhisperPhase.Transcribing ->
                    "Transcribing…"
                state.binaryReady && !state.modelReady -> "Voice binary ready — model pending"
                !state.binaryReady -> "Voice not installed"
                else -> "Voice pending"
            },
            color = if (state.isReady) PulseColors.Green else PulseColors.Fg,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        if (!state.isReady && !isWorking) {
            Box(
                modifier = Modifier
                    .background(PulseColors.Bg3, RectangleShape)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .clickable { scope.launch { whisper.prepare() } }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("Install", color = PulseColors.Fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (isWorking && (state.phase == com.pulseteam.desktop.data.voice.WhisperPhase.DownloadingBinary ||
                state.phase == com.pulseteam.desktop.data.voice.WhisperPhase.DownloadingModel)) {
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(PulseColors.Bg3, RectangleShape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(state.fraction.coerceIn(0f, 1f))
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

/**
 * Desktop control settings tab. Toggles the master enable, picks the
 * vision model, optionally stores the OpenAI API key, and shows the
 * status of the three underlying engines (screen capture, OCR, PC).
 */
@Composable
private fun DesktopPanel(
    onChange: () -> Unit,
    desktop: DesktopController?,
) {
    val settings by AppSettingsStore.state.collectAsState()
    var apiKeyInput by remember(settings.cloudApiKey) { mutableStateOf(settings.cloudApiKey) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Master enable
        ToggleRow(
            label = "Enable desktop control",
            initial = settings.desktopEnabled,
            onChange = {
                AppSettingsStore.update { it.copy(desktopEnabled = !settings.desktopEnabled) }
                onChange()
            },
        )

        // Safety level — user picks the policy
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, PulseColors.Border, RectangleShape)
                .background(PulseColors.Bg2, RectangleShape)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clickable {
                    val next = when (settings.safetyLevel) {
                        com.pulseteam.desktop.data.desktop.SafetyLevel.AlwaysConfirm -> com.pulseteam.desktop.data.desktop.SafetyLevel.OncePerCommand
                        com.pulseteam.desktop.data.desktop.SafetyLevel.OncePerCommand -> com.pulseteam.desktop.data.desktop.SafetyLevel.Never
                        com.pulseteam.desktop.data.desktop.SafetyLevel.Never -> com.pulseteam.desktop.data.desktop.SafetyLevel.AlwaysConfirm
                    }
                    AppSettingsStore.update { it.copy(safetyLevel = next) }
                    onChange()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Safety level", color = PulseColors.Fg, fontSize = 12.sp, modifier = Modifier.weight(1f))
            val label = when (settings.safetyLevel) {
                com.pulseteam.desktop.data.desktop.SafetyLevel.AlwaysConfirm -> "Always confirm"
                com.pulseteam.desktop.data.desktop.SafetyLevel.OncePerCommand -> "Once per command (5 min)"
                com.pulseteam.desktop.data.desktop.SafetyLevel.Never -> "Never (advanced)"
            }
            Text(label, color = PulseColors.FgDim, fontSize = 12.sp, style = MonoStyle)
            Spacer(Modifier.width(4.dp))
            Text("▾", color = PulseColors.FgDim, fontSize = 12.sp)
        }

        // Vision model picker
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, PulseColors.Border, RectangleShape)
                .background(PulseColors.Bg2, RectangleShape)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clickable {
                    val next = if (settings.visionModel == VisionModel.OcrOnly) VisionModel.OpenAiCloud else VisionModel.OcrOnly
                    AppSettingsStore.update { it.copy(visionModel = next) }
                    onChange()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Vision model", color = PulseColors.Fg, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                when (settings.visionModel) {
                    VisionModel.OcrOnly -> "OCR only (local)"
                    VisionModel.OpenAiCloud -> "OpenAI gpt-4o-mini (cloud)"
                },
                color = PulseColors.FgDim,
                fontSize = 12.sp,
                style = MonoStyle,
            )
            Spacer(Modifier.width(4.dp))
            Text("▾", color = PulseColors.FgDim, fontSize = 12.sp)
        }

        // OpenAI API key (only if cloud model selected)
        if (settings.visionModel == VisionModel.OpenAiCloud) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .background(PulseColors.Bg2, RectangleShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("OpenAI API key", color = PulseColors.Fg, fontSize = 12.sp, modifier = Modifier.weight(1f))
                androidx.compose.foundation.text.BasicTextField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it
                        AppSettingsStore.update { s -> s.copy(cloudApiKey = it) }
                        onChange()
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = PulseColors.Fg,
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PulseColors.Accent),
                    modifier = Modifier.width(220.dp),
                )
            }
        }

        // Engine status rows
        if (desktop != null) {
            Spacer(Modifier.height(8.dp))
            val isMac = remember {
                System.getProperty("os.name")?.lowercase()?.contains("mac") == true
            }
            var refreshTick by remember { mutableStateOf(0) }
            // Reading refreshTick inside the status rows makes Compose
            // re-evaluate them on Re-check.
            val ocrAvail = remember(refreshTick) { desktop.ocrAvailable }
            val ocrMsg = remember(refreshTick) { desktop.ocrStatus }
            val pcAvail = remember(refreshTick) { desktop.pcAvailable }
            val screenAvail = remember(refreshTick) { desktop.screenAvailable }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Engines", color = PulseColors.FgDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(PulseColors.Bg3, RectangleShape)
                        .border(1.dp, PulseColors.Border, RectangleShape)
                        .clickable {
                            desktop.recheckEngines()
                            refreshTick++
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Re-check", color = PulseColors.Fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(4.dp))
            EngineStatusRow("Screen capture", screenAvail, "ok")
            EngineStatusRow("OCR (tesseract)", ocrAvail, ocrMsg)
            EngineStatusRow("PC interaction", pcAvail, if (pcAvail) "ok" else "unavailable")

            // macOS: the system requires explicit Accessibility permission
            // for any process to control the screen / mouse / keyboard.
            // Without it, Robot methods silently no-op. We surface a
            // clear hint + a button to open the right System Settings pane.
            if (isMac && !pcAvail) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, PulseColors.Warn, RectangleShape)
                        .background(PulseColors.Bg2, RectangleShape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "macOS: grant Accessibility permission in System Settings → Privacy & Security → Accessibility, then click Re-check.",
                        color = PulseColors.Warn,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(PulseColors.Bg3, RectangleShape)
                            .border(1.dp, PulseColors.Border, RectangleShape)
                            .clickable {
                                // Apple URL scheme that opens the
                                // Accessibility pane directly.
                                try {
                                    java.awt.Desktop.getDesktop()
                                        .browse(java.net.URI("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"))
                                } catch (_: Throwable) {
                                    // No-op; some macOS versions reject
                                    // the URL scheme if Pulse isn't
                                    // already trusted.
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("Open System Settings", color = PulseColors.Fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // If OCR is missing on this host, give the user a one-click
            // way to copy the install command. On macOS we can also
            // auto-trigger `brew install tesseract` (no sudo needed);
            // on Linux/Windows we just copy the command so the user
            // can paste it into their shell.
            if (!ocrAvail) {
                Spacer(Modifier.height(8.dp))
                val isMac = remember {
                    System.getProperty("os.name")?.lowercase()?.contains("mac") == true
                }
                val isWin = remember {
                    System.getProperty("os.name")?.lowercase()?.contains("win") == true
                }
                val installCmd = when {
                    isMac -> "brew install tesseract"
                    isWin -> "winget install UB-Mannheim.tesseract"
                    else -> "sudo apt install tesseract-ocr"
                }
                val installHint = when {
                    isMac -> "Run via Homebrew (no sudo). Pulse will ask before launching."
                    isWin -> "Winget is bundled with Windows 10/11. Paste into PowerShell."
                    else -> "Requires sudo. Paste into a terminal and authenticate."
                }
                Text(installHint, color = PulseColors.FgDim, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        installCmd,
                        color = PulseColors.Fg,
                        fontSize = 11.sp,
                        style = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(PulseColors.Accent, RectangleShape)
                            .border(1.dp, PulseColors.Border, RectangleShape)
                            .clickable {
                                // Copy the install command to the system
                                // clipboard so the user can paste it
                                // into their shell.
                                val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                cb.setContents(java.awt.datatransfer.StringSelection(installCmd), null)
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("Copy", color = PulseColors.Bg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (isMac) {
                        Spacer(Modifier.width(8.dp))
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        var installing by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .background(if (installing) PulseColors.Bg else PulseColors.Bg3, RectangleShape)
                                .border(1.dp, PulseColors.Border, RectangleShape)
                                .clickable(enabled = !installing) {
                                    installing = true
                                    scope.launch {
                                        try {
                                            // Auto-install on macOS via brew.
                                            // No sudo needed; brew is the
                                            // user-space package manager.
                                            val pb = ProcessBuilder("brew", "install", "tesseract")
                                                .redirectErrorStream(true)
                                            val proc = pb.start()
                                            proc.inputStream.bufferedReader().use { it.readText() }
                                            proc.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)
                                        } catch (_: Throwable) {
                                            // best-effort; the user can
                                            // Re-check manually.
                                        } finally {
                                            desktop.recheckEngines()
                                            refreshTick++
                                            installing = false
                                        }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                if (installing) "Installing…" else "Auto-install (macOS)",
                                color = if (installing) PulseColors.FgDim else PulseColors.Fg,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineStatusRow(label: String, available: Boolean, status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            color = if (available) PulseColors.Green else PulseColors.Error,
            size = 8.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = PulseColors.Fg, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(status, color = PulseColors.FgDim, fontSize = 10.sp, style = MonoStyle)
    }
}





