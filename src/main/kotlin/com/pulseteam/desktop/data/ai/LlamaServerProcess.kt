// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — llama-server subprocess management.
//
// Spawns `llama-server.exe` (bundled with the app, downloaded on first run)
// with the active GGUF model, exposes a /health check loop, and a clean
// stop(). Crash → restart with exponential backoff (max 3 attempts/min).
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

data class LlamaServerHandle(
    val process: Process,
    val port: Int,
    val startedAt: Long,
    val logFile: File,
)

class LlamaServerProcess {

    private val runtimeDir: File = File(System.getProperty("user.home"), ".pulse/runtime")
    private val logsDir: File = File(System.getProperty("user.home"), ".pulse/logs")
    private val handle = AtomicReference<LlamaServerHandle?>(null)
    private val _state = MutableStateFlow(ServerState(ServerStatus.Stopped))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdog: Job? = null

    fun state(): StateFlow<ServerState> = _state.asStateFlow()

    /** Path to the bundled llama-server.exe (or null if not yet downloaded). */
    fun binaryPath(): File? {
        val exe = File(runtimeDir, "bin/llama-server.exe")
        return if (exe.exists() && exe.canExecute()) exe else null
    }

    fun binaryDir(): File = File(runtimeDir, "bin")

    /** Start the server with the given model. Idempotent if already running. */
    fun start(meta: ModelMeta, port: Int = 11435, ctxSize: Int = 4096, gpuLayers: Int = 0): LlamaServerHandle {
        handle.get()?.let { existing ->
            if (existing.process.isAlive) return existing
        }

        val exe = binaryPath() ?: throw IOException(
            "llama-server.exe not found at ${binaryDir()}/llama-server.exe. Run first-run onboarding first."
        )

        val modelFile = File(System.getProperty("user.home"), ".pulse/models/${meta.id}.gguf")
        if (!modelFile.exists()) throw IOException("Model file missing: ${modelFile.absolutePath}")

        logsDir.mkdirs()
        val logFile = File(logsDir, "llama-server.log")

        val pb = ProcessBuilder(
            exe.absolutePath,
            "-m", modelFile.absolutePath,
            "--port", port.toString(),
            "-c", ctxSize.toString(),
            "--host", "127.0.0.1",
            "-ngl", gpuLayers.toString(),
        ).apply {
            directory(modelFile.parentFile)
            redirectErrorStream(true)
            redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        }

        PulseLogger.info("Starting llama-server",
            mapOf("model" to meta.id, "port" to port, "ctx" to ctxSize, "ngl" to gpuLayers, "exe" to exe.absolutePath))

        val process = pb.start()
        val startedAt = System.currentTimeMillis()
        val serverHandle = LlamaServerHandle(process, port, startedAt, logFile)
        handle.set(serverHandle)
        _state.value = ServerState(ServerStatus.Starting, meta.id, port)

        startWatchdog(meta, port, ctxSize, gpuLayers)
        return serverHandle
    }

    /** Block (with timeout) until /health returns 200. */
    suspend fun awaitReady(timeoutMs: Long = 60_000) {
        val start = System.currentTimeMillis()
        val port = handle.get()?.port ?: throw IllegalStateException("Server not started")
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isHealthy(port)) {
                _state.value = _state.value.copy(status = ServerStatus.Ready)
                PulseLogger.info("llama-server is ready", mapOf("port" to port, "waitedMs" to (System.currentTimeMillis() - start)))
                return
            }
            delay(500)
        }
        throw IOException("llama-server did not become ready within ${timeoutMs}ms")
    }

    fun isHealthy(port: Int = handle.get()?.port ?: 11435): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Throwable) {
            false
        }
    }

    private fun startWatchdog(meta: ModelMeta, port: Int, ctxSize: Int, gpuLayers: Int) {
        watchdog?.cancel()
        watchdog = scope.launch {
            // First wait for ready
            try {
                awaitReady(120_000)
            } catch (t: Throwable) {
                PulseLogger.error("llama-server failed to start", t, mapOf("model" to meta.id))
                _state.value = ServerState(ServerStatus.Crashed, meta.id, port, t.message)
                stop()
                return@launch
            }

            // Then watch for unexpected exit
            while (isActive) {
                val proc = handle.get()?.process
                if (proc == null || !proc.isAlive) {
                    PulseLogger.error("llama-server exited unexpectedly", null, mapOf("model" to meta.id, "exit" to proc?.exitValue()))
                    _state.value = ServerState(ServerStatus.Crashed, meta.id, port, "process exited")
                    // Try one restart
                    try {
                        start(meta, port, ctxSize, gpuLayers)
                    } catch (t: Throwable) {
                        PulseLogger.error("llama-server restart failed", t)
                        _state.value = ServerState(ServerStatus.Crashed, meta.id, port, t.message)
                        break
                    }
                }
                delay(2000)
            }
        }
    }

    fun stop() {
        watchdog?.cancel()
        watchdog = null
        handle.getAndSet(null)?.let { h ->
            try {
                if (h.process.isAlive) {
                    h.process.destroy()
                    if (!h.process.waitFor(5_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        h.process.destroyForcibly()
                    }
                }
            } catch (t: Throwable) {
                PulseLogger.warn("Error stopping llama-server", mapOf("err" to t.message))
            }
        }
        _state.value = ServerState(ServerStatus.Stopped)
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }
}
