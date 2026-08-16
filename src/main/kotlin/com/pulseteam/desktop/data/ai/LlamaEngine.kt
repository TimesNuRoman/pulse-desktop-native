// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — real AI engine. Wraps llama-server (via LlamaClient) into
// the AiEngine interface. NO FALLBACK. If the server can't start, the
// caller (ChatViewModel) sees an IllegalStateException with a user-readable
// message that it renders in the chat bubble.
package com.pulseteam.desktop.data.ai

import com.pulseteam.desktop.data.log.PulseLogger
import com.pulseteam.desktop.data.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Real AI engine. Watches [AppSettingsStore.activeModelId] and ensures
 * the llama-server subprocess is running with that model, then proxies
 * streamReply calls to LlamaClient.
 *
 * On any failure to start the server, throws an [IllegalStateException]
 * with a user-readable message — no silent fallback, no canned response.
 * The chat UI catches and shows the message.
 */
class LlamaEngine(
    private val repository: ModelsRepository,
    private val server: LlamaServerProcess,
    private val client: LlamaClient,
) : AiEngine {

    override val name: String = "llama-server"

    @Volatile private var startedFor: String? = null

    override fun streamReply(userMessage: String): Flow<String> = flow {
        val activeModelId = ensureRunning()
        if (activeModelId == null) {
            // ensureRunning already threw a user-readable IllegalStateException.
            // If we got here without an exception, the server is healthy but
            // the model id didn't change. Defensive: re-throw a generic error.
            throw IllegalStateException(
                "AI server is not ready. Try again in a moment, or open Settings → Models."
            )
        }

        val settings = AppSettingsStore.state.value
        val req = ChatRequest(
            model = activeModelId,
            messages = listOf(ChatMessage("user", userMessage)),
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            topP = settings.topP,
            stream = true,
        )
        client.chatStream(req).collect { delta -> emit(delta) }
    }.flowOn(Dispatchers.IO)

    /**
     * Ensure the server is running with the active model. Returns the model
     * id if running. Throws [IllegalStateException] with a user-readable
     * message if not (no model installed, no runtime, server failed to
     * start, model download was interrupted, etc.).
     *
     * Strategy:
     *  1. Read AppSettings.activeModelId.
     *  2. If model file present and server already running for this model → return.
     *  3. Otherwise try to start the server. Throw on any failure.
     */
    private suspend fun ensureRunning(): String? {
        val activeId = AppSettingsStore.state.value.activeModelId
        val modelMeta = ModelRegistry.byId(activeId)
        if (modelMeta == null) {
            throw IllegalStateException(
                "No model is installed. Open Settings → Models to download one (Qwen 2.5 Coder 1.5B is a good start)."
            )
        }
        val modelFile = repository.findInstalled(modelMeta.id)
        if (modelFile == null) {
            throw IllegalStateException(
                "Model \"${modelMeta.displayName}\" is not downloaded. Open Settings → Models and click Download."
            )
        }

        if (startedFor == modelMeta.id && server.isHealthy()) {
            return modelMeta.id
        }

        // Check that the runtime binary is installed before trying to start.
        // Without this, LlamaServerProcess.start throws a generic IOException
        // that's not as helpful as "run first-run onboarding first".
        if (server.binaryPath() == null) {
            throw IllegalStateException(
                "AI runtime is not installed. The first-run download should have triggered automatically. If it didn't, open Settings → Models and click Install on the AI runtime row."
            )
        }

        return try {
            server.start(modelMeta)
            server.awaitReady(timeoutMs = 90_000)
            startedFor = modelMeta.id
            PulseLogger.info("LlamaEngine ready", mapOf("model" to modelMeta.id))
            modelMeta.id
        } catch (t: Throwable) {
            PulseLogger.error("Failed to start llama-server", t, mapOf("model" to modelMeta.id))
            startedFor = null
            throw IllegalStateException(
                "AI server failed to start. Check ~/.pulse/logs/llama-server.log for details. Error: ${t.message ?: t::class.java.simpleName}"
            )
        }
    }

    fun shutdown() {
        server.stop()
    }
}
