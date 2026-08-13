// SPDX-License-Identifier: Apache-2.0
// Pulse — WhisperTranscriber. Real on-device speech-to-text via whisper.cpp.
//
// v0.7.0: replaces the "Whisper.cpp wires up in v0.8" stub in Main.kt.
// We follow the same pattern as the llama-server runtime:
//   1. Lazily download a small whisper.cpp CLI binary zip on first use
//   2. Lazily download a ggml-tiny.bin model (75 MB, multilingual)
//   3. Spawn `whisper-cli -m model -f audio -l auto --no-timestamps --output-json`
//   4. Parse the JSON output, return the concatenated text
//
// Why whisper-cli (not whisper-server):
//   - Voice is low-frequency (one file per click), so a keep-alive server
//     buys us nothing
//   - Synchronous CLI is easier to reason about: success/failure is a
//     process exit code, no port conflicts with llama-server (which uses
//     11435; we use the same port range +1 to keep it free)
//
// Audio formats: whisper-cli links libavformat, so it accepts WAV / MP3 /
// M4A / OGG / FLAC / OPUS directly. We don't need ffmpeg as a separate
// dependency.
//
// Storage:
//   binary:  ~/.pulse/runtime/bin/whisper-cli.exe (+ sibling .dll files)
//   model:   ~/.pulse/models/ggml-tiny.bin
package com.pulseteam.desktop.data.voice

import com.pulseteam.desktop.data.log.PulseLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Progress callback for the Settings UI to render a progress bar. */
fun interface WhisperProgressListener {
    fun onProgress(phase: String, fraction: Float, message: String?)
}

/** UI-visible state of the whisper runtime + model. */
data class WhisperState(
    val binaryReady: Boolean = false,
    val modelReady: Boolean = false,
    val phase: WhisperPhase = WhisperPhase.Idle,
    val fraction: Float = 0f,
    val message: String? = null,
    val error: String? = null,
) {
    val isReady: Boolean get() = binaryReady && modelReady && phase == WhisperPhase.Idle
}

enum class WhisperPhase { Idle, DownloadingBinary, ExtractingBinary, DownloadingModel, Transcribing }

class WhisperTranscriber {

    /** Where whisper-cli.exe + .dlls live. Matches llama-server layout. */
    private val runtimeDir: File = File(System.getProperty("user.home"), ".pulse/runtime/bin")

    /** Where whisper model files live. Matches llama model layout. */
    private val modelsDir: File = File(System.getProperty("user.home"), ".pulse/models")

    /** whisper.cpp v1.8.4 (Mar 2026, latest stable as of writing). */
    private val binaryUrl = "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.4/whisper-bin-x64.zip"
    private val binaryZip = File(runtimeDir, "whisper-bin-x64.zip")
    private val binaryExe = File(runtimeDir, "whisper-cli.exe")

    /** tiny = 75 MB, multilingual. Base = 142 MB. We start with tiny. */
    private val modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin?download=true"
    private val modelFile = File(modelsDir, "ggml-tiny.bin")
    private val modelPartial = File(modelsDir, "ggml-tiny.bin.partial")

    private val _state = MutableStateFlow(
        WhisperState(
            binaryReady = binaryInstalled(),
            modelReady = modelInstalled(),
        )
    )
    val state: StateFlow<WhisperState> = _state.asStateFlow()

    /**
     * Install just the binary + model (no transcription). Safe to call from
     * the Settings screen — runs in withContext(Dispatchers.IO). Idempotent.
     */
    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureBinaryInstalled { phase, frac, msg -> publish(phase.toWhisperPhase(), frac, msg) }
            ensureModelInstalled { phase, frac, msg -> publish(phase.toWhisperPhase(), frac, msg) }
            publish(WhisperPhase.Idle, 1.0f, null)
            true
        } catch (t: Throwable) {
            _state.value = _state.value.copy(
                phase = WhisperPhase.Idle,
                error = t.message ?: t::class.java.simpleName,
            )
            PulseLogger.error("Whisper: prepare failed", t)
            false
        }
    }

    private fun publish(phase: WhisperPhase, fraction: Float, message: String?) {
        _state.value = _state.value.copy(
            phase = phase,
            fraction = fraction,
            message = message,
            binaryReady = binaryInstalled(),
            modelReady = modelInstalled(),
        )
    }

    private fun String.toWhisperPhase(): WhisperPhase = when (this) {
        "binary" -> WhisperPhase.DownloadingBinary
        "model" -> WhisperPhase.DownloadingModel
        "transcribe" -> WhisperPhase.Transcribing
        else -> WhisperPhase.Idle
    }

    /**
     * Transcribe an audio file. Returns the text on success, or null on any
     * failure (download error, missing model, whisper non-zero exit, etc.).
     *
     * The function is idempotent: it downloads the binary + model only once
     * and reuses them across calls. A failed download leaves a *.partial
     * file behind that gets cleaned up before the next attempt.
     */
    suspend fun transcribe(
        audio: File,
        listener: WhisperProgressListener? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (!audio.exists() || audio.length() == 0L) {
            PulseLogger.warn("Whisper: audio file missing or empty", mapOf("file" to audio.absolutePath))
            return@withContext null
        }
        try {
            ensureBinaryInstalled(listener)
            ensureModelInstalled(listener)
            runWhisperCli(audio, listener)
        } catch (t: Throwable) {
            PulseLogger.error("Whisper: transcribe failed", t)
            null
        }
    }

    /** Public status for the Settings UI. */
    fun isReady(): Boolean = binaryExe.exists() && binaryExe.canExecute() && modelFile.exists() && modelFile.length() > 1_000_000L

    fun binaryInstalled(): Boolean = binaryExe.exists() && binaryExe.canExecute()
    fun modelInstalled(): Boolean = modelFile.exists() && modelFile.length() > 1_000_000L

    // ------------------------------------------------------------------
    // Installation
    // ------------------------------------------------------------------

    private fun ensureBinaryInstalled(listener: WhisperProgressListener?) {
        if (binaryInstalled()) return
        listener?.onProgress("binary", 0f, "Downloading whisper.cpp v1.8.4…")
        runtimeDir.mkdirs()
        downloadWithProgress(binaryUrl, binaryZip) { frac ->
            listener?.onProgress("binary", frac, "Downloading whisper.cpp… ${(frac * 100).toInt()}%")
        }
        listener?.onProgress("binary", 1.0f, "Extracting…")
        extractZipOverwrite(binaryZip, runtimeDir)
        // The zip extracts whisper-cli.exe into a `Release/` subdir. Move
        // every extracted file up one level for a flat layout.
        val releaseDir = File(runtimeDir, "Release")
        if (releaseDir.isDirectory) {
            releaseDir.listFiles()?.forEach { f ->
                val target = File(runtimeDir, f.name)
                if (!target.exists()) Files.move(f.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            releaseDir.delete()
        }
        binaryZip.delete()  // keep runtime dir tidy
        if (!binaryInstalled()) {
            throw RuntimeException("whisper-cli.exe not found after extraction (looked in $runtimeDir)")
        }
        PulseLogger.info("Whisper runtime installed", mapOf("dir" to runtimeDir.absolutePath))
    }

    private fun ensureModelInstalled(listener: WhisperProgressListener?) {
        if (modelInstalled()) return
        listener?.onProgress("model", 0f, "Downloading ggml-tiny.bin model (75 MB, multilingual)…")
        modelsDir.mkdirs()
        if (modelPartial.exists()) modelPartial.delete()
        downloadWithProgress(modelUrl, modelPartial) { frac ->
            listener?.onProgress("model", frac, "Downloading model… ${(frac * 100).toInt()}%")
        }
        // Atomic rename so we never have a half-written model on success.
        Files.move(modelPartial.toPath(), modelFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        PulseLogger.info("Whisper model installed", mapOf("file" to modelFile.absolutePath, "bytes" to modelFile.length()))
    }

    // ------------------------------------------------------------------
    // whisper-cli invocation
    // ------------------------------------------------------------------

    private fun runWhisperCli(audio: File, listener: WhisperProgressListener?): String? {
        listener?.onProgress("transcribe", 0f, "Transcribing…")
        val cmd = listOf(
            binaryExe.absolutePath,
            "-m", modelFile.absolutePath,
            "-f", audio.absolutePath,
            "-l", "auto",                  // auto-detect language
            "--no-timestamps",
            "--output-json",
            "--print-progress",            // whisper prints "whisper_print_timings" + progress %
            "-t", "4",                     // 4 threads (typical mid-range laptop)
        )
        val proc = ProcessBuilder(cmd)
            .directory(runtimeDir)
            .redirectErrorStream(true)
            .start()
        // Stream stdout/stderr to a log file for post-mortem, plus capture
        // for the parser.
        val logFile = File(System.getProperty("user.home"), ".pulse/logs/whisper-cli.log")
        logFile.parentFile?.mkdirs()
        val sb = StringBuilder()
        val logOut = logFile.outputStream().buffered()
        proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                sb.append(line).append('\n')
                logOut.write(line.toByteArray(Charsets.UTF_8))
                logOut.write('\n'.code)
            }
        }
        logOut.close()
        val finished = proc.waitFor(10, TimeUnit.MINUTES)
        if (!finished) {
            proc.destroyForcibly()
            PulseLogger.error("Whisper: timed out after 10 min", null)
            return null
        }
        if (proc.exitValue() != 0) {
            PulseLogger.error("Whisper: non-zero exit", null, mapOf("exit" to proc.exitValue(), "out" to sb.toString().take(500)))
            return null
        }
        listener?.onProgress("transcribe", 1.0f, "Done")
        return parseJsonOutput(sb.toString())
    }

    /**
     * whisper-cli --output-json prints one top-level object per "segment".
     * Each segment has a "text" field. We concatenate them with spaces.
     *
     * Example (simplified):
     *   {"text": " hello world", ...}
     *   {"text": " this is a test", ...}
     *
     * We use a regex to avoid pulling in kotlinx-serialization just for this.
     */
    private fun parseJsonOutput(stdout: String): String? {
        val textFields = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(stdout)
            .map { it.groupValues[1] }
            .map { unescapeJson(it) }
            .toList()
        if (textFields.isEmpty()) return null
        val joined = textFields.joinToString(" ").trim()
        return joined.ifBlank { null }
    }

    private fun unescapeJson(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(c); i++
                    }
                    else -> { sb.append(c); sb.append(s[i + 1]); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // HTTP download with progress (shared with ModelDownloader pattern)
    // ------------------------------------------------------------------

    private fun downloadWithProgress(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Pulse/1.0 (desktop; +https://ownlocalml.com)")
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw RuntimeException("HTTP $code downloading $url")
        }
        val total = conn.contentLengthLong.coerceAtLeast(1L)
        dest.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(64 * 1024)
                var downloaded = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    downloaded += n
                    val now = System.currentTimeMillis()
                    // Throttle progress reports to 200ms.
                    if (now - lastReport > 200) {
                        onProgress((downloaded.toFloat() / total).coerceIn(0f, 0.99f))
                        lastReport = now
                    }
                }
                output.flush()
            }
        }
        conn.disconnect()
        onProgress(1.0f)
    }

    private fun extractZipOverwrite(zip: File, destDir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zin.copyTo(out) }
                }
                zin.closeEntry()
            }
        }
    }
}
