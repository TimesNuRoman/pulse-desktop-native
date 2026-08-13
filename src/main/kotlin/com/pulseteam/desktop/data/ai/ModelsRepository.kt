// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — models repository. Single source of truth for "which
// models are installed + which is active + which is downloading".
//
// v0.7.0: replaces hardcoded list in SettingsScreen, brings real
// "downloaded" state and progress reporting.
package com.pulseteam.desktop.data.ai

import com.pulseteam.desktop.data.log.PulseLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** One row in the Models panel + onboarding picker. */
data class ModelEntry(
    val meta: ModelMeta,
    val installed: Boolean,
    val installedBytes: Long,
)

/** Server runtime status (llama-server health). */
enum class ServerStatus { Stopped, Starting, Ready, Crashed, Disabled }

data class ServerState(
    val status: ServerStatus,
    val modelId: String? = null,
    val port: Int = 0,
    val lastError: String? = null,
)

class ModelsRepository {

    private val modelsDir: File = File(System.getProperty("user.home"), ".pulse/models")
    private val downloader = ModelDownloader()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val _entries = MutableStateFlow<List<ModelEntry>>(emptyList())
    private val _server = MutableStateFlow(ServerState(ServerStatus.Stopped))

    fun entries(): StateFlow<List<ModelEntry>> = _entries.asStateFlow()
    fun server(): StateFlow<ServerState> = _server.asStateFlow()
    fun downloader(): ModelDownloader = downloader

    init {
        refresh()
    }

    fun refresh() {
        if (!modelsDir.exists()) modelsDir.mkdirs()
        val installedFiles = modelsDir.listFiles { f -> f.extension == "gguf" }
            ?.associateBy { it.nameWithoutExtension } ?: emptyMap()
        val list = ModelRegistry.MODEL_CATALOG.map { meta ->
            val file = installedFiles[meta.id]
            ModelEntry(
                meta = meta,
                installed = file != null && file.length() > 1_000_000,
                installedBytes = file?.length() ?: 0L,
            )
        }
        _entries.value = list
        PulseLogger.info("Models repository refreshed",
            mapOf("installed" to list.count { it.installed }, "total" to list.size))
    }

    fun startDownload(meta: ModelMeta): ActiveDownload? {
        if (pollJob?.isActive == true) return null  // already downloading
        PulseLogger.info("Starting model download",
            mapOf("id" to meta.id, "sizeMB" to meta.sizeBytes / 1_000_000))
        val download = downloader.start(meta) ?: return null
        pollJob = scope.launch {
            while (isActive) {
                val s = download.state.value
                if (s == DownloadState.Done || s == DownloadState.Failed || s == DownloadState.Canceled) {
                    refresh()
                    break
                }
                delay(500)
            }
        }
        return download
    }

    fun deleteInstalled(meta: ModelMeta): Boolean {
        val ok = downloader.deleteModelFile(meta.id)
        if (ok) refresh()
        return ok
    }

    fun updateServer(state: ServerState) {
        _server.value = state
    }

    fun modelFilePath(meta: ModelMeta): File = File(modelsDir, "${meta.id}.gguf")

    fun findInstalled(id: String): File? {
        val f = File(modelsDir, "$id.gguf")
        return if (f.exists() && f.length() > 1_000_000) f else null
    }

    fun shutdown() {
        scope.cancel()
    }
}
