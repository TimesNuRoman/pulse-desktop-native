// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — model downloader. Streams a GGUF file from HuggingFace
// with progress reporting and SHA-256 verification.
//
// Resumable: if a .partial file exists, we send a Range: header and
// continue from where we left off. The partial file is renamed to
// the final filename only after hash verification passes.
package com.pulseteam.desktop.data.ai

import com.pulseteam.desktop.data.log.PulseLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Progress event emitted by [ModelDownloader.download]. */
data class DownloadProgress(
    val modelId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedMBps: Double,
    val etaSeconds: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (downloadedBytes.toDouble() / totalBytes).toFloat() else 0f
}

enum class DownloadState { Idle, Downloading, Verifying, Done, Failed, Canceled }

/** Active download (one at a time per downloader instance). */
class ActiveDownload internal constructor(
    val modelId: String,
    val state: StateFlow<DownloadState>,
    val progress: StateFlow<DownloadProgress>,
)

class ModelDownloader {

    private val modelsDir: File = File(System.getProperty("user.home"), ".pulse/models")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    private val _progress = MutableStateFlow(DownloadProgress("", 0, 0, 0.0, 0))
    private val _modelId = MutableStateFlow("")

    private var currentJob: Job? = null

    fun state(): StateFlow<DownloadState> = _state.asStateFlow()
    fun progress(): StateFlow<DownloadProgress> = _progress.asStateFlow()
    fun currentModelId(): StateFlow<String> = _modelId.asStateFlow()

    /** Start a download. If one is in progress, returns null. */
    fun start(meta: ModelMeta): ActiveDownload? {
        if (_state.value == DownloadState.Downloading) return null

        modelsDir.mkdirs()
        val finalFile = File(modelsDir, "${meta.id}.gguf")
        val partialFile = File(modelsDir, "${meta.id}.gguf.partial")

        currentJob = scope.launch {
            try {
                _modelId.value = meta.id
                _state.value = DownloadState.Downloading
                _progress.value = DownloadProgress(meta.id, 0, meta.sizeBytes, 0.0, 0)

                // Skip if already fully downloaded AND hash matches (if known).
                if (finalFile.exists() && finalFile.length() == meta.sizeBytes) {
                    if (meta.sha256.isEmpty() || verifyHash(finalFile, meta.sha256)) {
                        PulseLogger.info("Model already present", mapOf("id" to meta.id, "bytes" to finalFile.length()))
                        _state.value = DownloadState.Done
                        return@launch
                    }
                }

                val totalBytes = queryTotalSize(meta.hfUrl, partialFile.length())
                if (totalBytes > 0 && partialFile.length() >= totalBytes) {
                    // Partial file claims to be complete, verify and rename.
                    if (meta.sha256.isEmpty() || verifyHash(partialFile, meta.sha256)) {
                        if (partialFile.renameTo(finalFile)) {
                            _progress.value = DownloadProgress(meta.id, totalBytes, totalBytes, 0.0, 0)
                            _state.value = DownloadState.Done
                            return@launch
                        }
                    }
                }

                downloadLoop(meta, partialFile, totalBytes)

                // Hash check (if expected hash known)
                if (meta.sha256.isNotEmpty() && !verifyHash(partialFile, meta.sha256)) {
                    PulseLogger.error("Model hash mismatch", null, mapOf("id" to meta.id, "expected" to meta.sha256))
                    partialFile.delete()
                    _state.value = DownloadState.Failed
                    return@launch
                }

                if (!partialFile.renameTo(finalFile)) {
                    PulseLogger.error("Failed to rename partial to final", null, mapOf("id" to meta.id))
                    _state.value = DownloadState.Failed
                    return@launch
                }

                PulseLogger.info("Model downloaded", mapOf("id" to meta.id, "bytes" to finalFile.length()))
                _state.value = DownloadState.Done
            } catch (ce: kotlinx.coroutines.CancellationException) {
                _state.value = DownloadState.Canceled
                throw ce
            } catch (t: Throwable) {
                PulseLogger.error("Model download failed", t, mapOf("id" to meta.id))
                _state.value = DownloadState.Failed
            }
        }

        return ActiveDownload(meta.id, _state.asStateFlow(), _progress.asStateFlow())
    }

    fun cancel() {
        currentJob?.cancel()
    }

    fun shutdown() {
        scope.cancel()
    }

    private suspend fun downloadLoop(meta: ModelMeta, partialFile: File, totalBytes: Long) = withContext(Dispatchers.IO) {
        val resumeFrom = partialFile.length()
        val url = URL(meta.hfUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "Pulse/1.0 (desktop; +https://ownlocalml.com)")
        if (resumeFrom > 0) {
            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        conn.connect()

        if (conn.responseCode !in setOf(200, 206)) {
            conn.disconnect()
            throw IOException("HTTP ${conn.responseCode} from ${meta.hfUrl}")
        }

        val raf = RandomAccessFile(partialFile, "rw")
        try {
            raf.seek(resumeFrom)
            val input = conn.inputStream
            val buf = ByteArray(64 * 1024)
            val startMs = System.currentTimeMillis()
            var lastReportMs = startMs
            var downloadedThisRun = 0L
            var totalDownloaded = resumeFrom

            while (isActive) {
                val n = input.read(buf)
                if (n <= 0) break
                raf.write(buf, 0, n)
                downloadedThisRun += n
                totalDownloaded += n

                val now = System.currentTimeMillis()
                if (now - lastReportMs >= 200) {
                    val elapsedSec = (now - startMs) / 1000.0
                    val speed = if (elapsedSec > 0) (downloadedThisRun / elapsedSec / 1_000_000.0) else 0.0
                    val total = if (totalBytes > 0) totalBytes else meta.sizeBytes
                    val remaining = (total - totalDownloaded).coerceAtLeast(0)
                    val eta = if (speed > 0) (remaining / (speed * 1_000_000)).toLong() else 0
                    _progress.value = DownloadProgress(meta.id, totalDownloaded, total, speed, eta)
                    lastReportMs = now
                }
            }
        } finally {
            try { raf.close() } catch (_: Throwable) {}
            conn.disconnect()
        }
    }

    private fun queryTotalSize(url: String, currentSize: Long): Long {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Pulse/1.0 (desktop; +https://ownlocalml.com)")
            conn.connect()
            val code = conn.responseCode
            if (code in setOf(200, 206)) {
                val contentRange = conn.getHeaderField("Content-Range")  // "bytes 100-200/300"
                val total = contentRange?.substringAfter('/')?.toLongOrNull()
                    ?: conn.getHeaderField("Content-Length")?.toLongOrNull()
                    ?: 0L
                conn.disconnect()
                total
            } else {
                conn.disconnect()
                0L
            }
        } catch (t: Throwable) {
            PulseLogger.warn("HEAD request failed, falling back to catalog size", mapOf("url" to url, "err" to t.message))
            0L
        }
    }

    private fun verifyHash(file: File, expectedHex: String): Boolean {
        if (expectedHex.isEmpty()) return true
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex, ignoreCase = true)
    }

    fun deleteModelFile(modelId: String): Boolean {
        val final = File(modelsDir, "$modelId.gguf")
        val partial = File(modelsDir, "$modelId.gguf.partial")
        var ok = true
        if (final.exists()) ok = final.delete() && ok
        if (partial.exists()) ok = partial.delete() && ok
        return ok
    }
}
