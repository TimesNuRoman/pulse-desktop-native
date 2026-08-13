// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — real AI engine. Wraps llama-server (via LlamaClient) into
// the AiEngine interface. Falls back to LocalMockEngine if the server isn't
// up (no model downloaded, or llama-server.exe not yet extracted).
package com.pulseteam.desktop.data.ai

import com.pulseteam.desktop.data.log.PulseLogger
import com.pulseteam.desktop.data.settings.AppSettings
import com.pulseteam.desktop.data.settings.AppSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

/**
 * Real AI engine. Watches [AppSettings.activeModelId] and ensures the
 * llama-server subprocess is running with that model, then proxies
 * streamReply calls to LlamaClient.
 */
class LlamaEngine(
    private val repository: ModelsRepository,
    private val server: LlamaServerProcess,
    private val client: LlamaClient,
    private val fallback: LocalMockEngine,
) : AiEngine {

    override val name: String = "llama-server"

    @Volatile private var startedFor: String? = null

    override fun streamReply(userMessage: String): Flow<String> = flow {
        val activeModelId = ensureRunning()
        if (activeModelId == null) {
            // Server not running — fall back to mock so the chat still
            // responds. Logged warning so user knows real AI isn't engaged.
            PulseLogger.warn("llama-server not running, falling back to LocalMockEngine")
            emitAll(fallback.streamReply(userMessage))
            return@flow
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
     * id if running, null if not (and no fallback is possible).
     *
     * Strategy:
     *  1. Read AppSettings.activeModelId (default: first installed).
     *  2. If server already running for this model → return.
     *  3. If model file present, start server with that model.
     *  4. If start fails (runtime missing, model missing) → return null.
     */
    private suspend fun ensureRunning(): String? {
        val activeId = AppSettingsStore.state.value.activeModelId
        val modelMeta = ModelRegistry.byId(activeId) ?: return null
        val modelFile = repository.findInstalled(modelMeta.id) ?: return null

        if (startedFor == modelMeta.id && server.isHealthy()) {
            return modelMeta.id
        }

        // Start the server (synchronous from caller's perspective: returns
        // immediately with the Process handle; awaitReady() inside is
        // called by caller via next flowOn step).
        return try {
            server.start(modelMeta)
            server.awaitReady(timeoutMs = 90_000)
            startedFor = modelMeta.id
            PulseLogger.info("LlamaEngine ready", mapOf("model" to modelMeta.id))
            modelMeta.id
        } catch (t: Throwable) {
            PulseLogger.error("Failed to start llama-server", t, mapOf("model" to modelMeta.id))
            startedFor = null
            null
        }
    }

    fun shutdown() {
        server.stop()
    }
}
