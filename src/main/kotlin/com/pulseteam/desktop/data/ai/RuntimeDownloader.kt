// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — runtime downloader. Fetches llama-server.exe + required
// DLLs from the official llama.cpp release zip on first run.
//
// Source: github.com/ggml-org/llama.cpp/releases
// Default: CPU-only build (~25 MB) — no GPU driver deps. Works on every
// Win 10+ machine out of the box. Power users can swap to CUDA build
// manually by replacing the binary.
package com.pulseteam.desktop.data.ai

import com.pulseteam.desktop.data.log.PulseLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

enum class RuntimeState { Idle, Downloading, Extracting, Done, Failed, Canceled }

data class RuntimeProgress(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val state: RuntimeState = RuntimeState.Idle,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (downloadedBytes.toDouble() / totalBytes).toFloat() else 0f
}

class RuntimeDownloader {

    private val runtimeDir: File = File(System.getProperty("user.home"), ".pulse/runtime")
    private val binDir: File = File(runtimeDir, "bin")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow(RuntimeProgress())
    fun progress(): StateFlow<RuntimeProgress> = _progress.asStateFlow()

    /** Target asset name in the release. Update when bumping version. */
    private val releaseTag = "b10333"
    private val assetName = "llama-$releaseTag-bin-win-cpu-x64.zip"
    private val downloadUrl = "https://github.com/ggml-org/llama.cpp/releases/download/$releaseTag/$assetName"

    fun isInstalled(): Boolean {
        // b10333+ uses a small launcher exe (9 KB) + llama-server-impl.dll (~10 MB).
        // The impl.dll is the real binary; check both to be sure.
        val exe = File(binDir, "llama-server.exe")
        val impl = File(binDir, "llama-server-impl.dll")
        return exe.exists() && exe.length() > 0 && impl.exists() && impl.length() > 1_000_000
    }

    fun binaryPath(): File? = if (isInstalled()) File(binDir, "llama-server.exe") else null

    fun startDownload() {
        if (_progress.value.state == RuntimeState.Downloading) return
        scope.launch {
            try {
                _progress.value = RuntimeProgress(state = RuntimeState.Downloading)

                runtimeDir.mkdirs()
                val zipFile = File(runtimeDir, assetName)

                // Download
                downloadWithProgress(downloadUrl, zipFile)

                // Extract
                _progress.value = _progress.value.copy(state = RuntimeState.Extracting)
                extractZip(zipFile, binDir)

                // Cleanup
                zipFile.delete()

                _progress.value = RuntimeProgress(state = RuntimeState.Done, downloadedBytes = _progress.value.totalBytes, totalBytes = _progress.value.totalBytes)
                PulseLogger.info("llama-server runtime installed", mapOf("path" to binaryPath()?.absolutePath))
            } catch (t: Throwable) {
                PulseLogger.error("Runtime download failed", t)
                _progress.value = RuntimeProgress(state = RuntimeState.Failed, error = t.message)
            }
        }
    }

    private suspend fun downloadWithProgress(url: String, dest: File) = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "Pulse/1.0 (desktop; +https://ownlocalml.com)")
        conn.connect()

        val total = conn.contentLengthLong.coerceAtLeast(0)
        _progress.value = _progress.value.copy(totalBytes = total, downloadedBytes = 0)

        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(64 * 1024)
                var downloaded = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    downloaded += n
                    _progress.value = _progress.value.copy(downloadedBytes = downloaded)
                }
            }
        }
    }

    private suspend fun extractZip(zipFile: File, destDir: File) = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = File(destDir, entry.name.substringAfterLast('/'))
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                }
                entry = zis.nextEntry
            }
        }
    }
}
