// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — first-run onboarding. Shown when no llama-server runtime
// is present or no model is installed. Lets the user download what's
// needed and only then enters the main app.
//
// v0.7.0: real runtime (llama-server.exe) + real model (GGUF) downloads.
// No mocks. After onboarding, Main.kt proceeds to the main app.
package com.pulseteam.desktop.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseteam.desktop.data.ai.ActiveDownload
import com.pulseteam.desktop.data.ai.DownloadState
import com.pulseteam.desktop.data.ai.ModelEntry
import com.pulseteam.desktop.data.ai.ModelMeta
import com.pulseteam.desktop.data.ai.ModelsRepository
import com.pulseteam.desktop.data.ai.RuntimeDownloader
import com.pulseteam.desktop.data.ai.RuntimeState
import com.pulseteam.desktop.ui.common.MonoLabel
import com.pulseteam.desktop.ui.common.StatusDot
import com.pulseteam.desktop.ui.theme.PulseColors

@Composable
fun OnboardingScreen(
    repository: ModelsRepository,
    runtime: RuntimeDownloader,
    onReady: () -> Unit,
) {
    val entries by repository.entries().collectAsState()
    val runtimeProgress by runtime.progress().collectAsState()

    val runtimeReady = runtime.isInstalled()
    val modelReady = entries.any { it.installed }
    val canEnter = runtimeReady && modelReady

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PulseColors.Bg)
            .padding(60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PULSE", color = PulseColors.Accent, fontSize = 36.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text("Local-first notes + chat + AI", color = PulseColors.FgDim, fontSize = 12.sp)
        Spacer(Modifier.height(40.dp))

        Box(
            modifier = Modifier
                .width(640.dp)
                .background(PulseColors.Bg2, RectangleShape)
                .border(1.dp, PulseColors.BorderStrong, RectangleShape)
                .padding(28.dp),
        ) {
            Column {
                Text("1. AI runtime", color = PulseColors.FgBright, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("llama-server.exe from llama.cpp (CPU build, ~25 MB)", color = PulseColors.FgDim, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                RuntimeRow(runtimeReady, runtimeProgress, onDownload = { runtime.startDownload() })
                Spacer(Modifier.height(24.dp))

                Text("2. Model", color = PulseColors.FgBright, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Pick a model to run on this device. One is enough to start.", color = PulseColors.FgDim, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                ModelList(
                    entries = entries,
                    onDownload = { meta -> repository.startDownload(meta) },
                    onDelete = { meta -> repository.deleteInstalled(meta) },
                )
                Spacer(Modifier.height(28.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(
                            if (canEnter) PulseColors.Accent else PulseColors.Bg3,
                            RectangleShape,
                        )
                        .border(
                            width = 1.dp,
                            color = if (canEnter) PulseColors.Accent else PulseColors.Border,
                            shape = RectangleShape,
                        )
                        .clickable(enabled = canEnter) { onReady() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            !runtimeReady -> "Download the runtime to continue"
                            !modelReady -> "Download a model to continue"
                            else -> "Open Pulse"
                        },
                        color = if (canEnter) PulseColors.Bg else PulseColors.FgDim,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeRow(
    installed: Boolean,
    progress: com.pulseteam.desktop.data.ai.RuntimeProgress,
    onDownload: () -> Unit,
) {
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
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                when {
                    installed -> "Installed (CPU-only build, ~25 MB)"
                    progress.state == RuntimeState.Downloading -> "Downloading… ${(progress.fraction * 100).toInt()}%"
                    progress.state == RuntimeState.Extracting -> "Extracting…"
                    progress.state == RuntimeState.Failed -> "Failed: ${progress.error ?: "unknown"}"
                    else -> "Not installed"
                },
                color = if (installed) PulseColors.Green else PulseColors.Fg,
                fontSize = 12.sp,
            )
            if (progress.state == RuntimeState.Downloading || progress.state == RuntimeState.Extracting) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(PulseColors.Bg3, RectangleShape),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.fraction.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(PulseColors.Accent, RectangleShape),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        if (!installed && progress.state != RuntimeState.Downloading && progress.state != RuntimeState.Extracting) {
            Box(
                modifier = Modifier
                    .background(PulseColors.Accent, RectangleShape)
                    .clickable { onDownload() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("Download", color = PulseColors.Bg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ModelList(
    entries: List<ModelEntry>,
    onDownload: (ModelMeta) -> ActiveDownload?,
    onDelete: (ModelMeta) -> Unit,
) {
    if (entries.isEmpty()) {
        Text("Loading…", color = PulseColors.FgDim, fontSize = 11.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { entry ->
            ModelRow(entry = entry, onDownload = { onDownload(entry.meta) }, onDelete = { onDelete(entry.meta) })
        }
    }
}

@Composable
private fun ModelRow(
    entry: ModelEntry,
    onDownload: () -> ActiveDownload?,
    onDelete: () -> Unit,
) {
    var activeDownload by remember { mutableStateOf<ActiveDownload?>(null) }
    val idleState = remember { kotlinx.coroutines.flow.MutableStateFlow(DownloadState.Idle) }
    val idleProgress = remember { kotlinx.coroutines.flow.MutableStateFlow(com.pulseteam.desktop.data.ai.DownloadProgress(entry.meta.id, 0, 0, 0.0, 0)) }
    val downloadState = (activeDownload?.state ?: idleState).collectAsState().value
    val progress = (activeDownload?.progress ?: idleProgress).collectAsState().value

    val borderColor = when {
        entry.installed -> PulseColors.Green
        downloadState == DownloadState.Downloading -> PulseColors.Warn
        downloadState == DownloadState.Failed -> PulseColors.Error
        else -> PulseColors.Border
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(PulseColors.Bg3, RectangleShape)
            .border(1.dp, borderColor, RectangleShape)
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonoLabel(entry.meta.displayName, color = PulseColors.FgBright)
                        Spacer(Modifier.width(8.dp))
                        Text(entry.meta.quant, color = PulseColors.FgDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(entry.meta.description, color = PulseColors.FgDim, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Size: ${entry.meta.sizeBytes / 1_000_000} MB · RAM ${entry.meta.minRamGb} GB${if (entry.meta.vramHintGb > 0) " · VRAM ${entry.meta.vramHintGb} GB" else ""}",
                        color = PulseColors.FgDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.width(8.dp))
                when {
                    entry.installed -> {
                        Box(
                            modifier = Modifier
                                .background(PulseColors.Green.copy(alpha = 0.18f), RectangleShape)
                                .border(1.dp, PulseColors.Green, RectangleShape)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("Installed", color = PulseColors.Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    downloadState == DownloadState.Downloading -> {
                        Text(
                            "${(progress.fraction * 100).toInt()}%",
                            color = PulseColors.Warn,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    downloadState == DownloadState.Failed -> {
                        Box(
                            modifier = Modifier
                                .background(PulseColors.Error, RectangleShape)
                                .clickable {
                                    activeDownload = onDownload()
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("Retry", color = PulseColors.Bg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .background(PulseColors.Accent, RectangleShape)
                                .clickable {
                                    activeDownload = onDownload()
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text("Download", color = PulseColors.Bg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (downloadState == DownloadState.Downloading) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(PulseColors.Bg2, RectangleShape),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.fraction.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(PulseColors.Warn, RectangleShape),
                    )
                }
            }
        }
    }
}
