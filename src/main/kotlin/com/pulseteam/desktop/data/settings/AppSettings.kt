// SPDX-License-Identifier: Apache-2.0
// Pulse — local app settings. Persists to ~/.pulse/settings.properties.
// Exposed as StateFlow so the UI reactively reflects changes.
package com.pulseteam.desktop.data.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

enum class RoutingMode { LocalOnly, ApiOnly, Hybrid }

data class AppSettings(
    val activeModelId: String = "qwen2.5-coder:7b",
    val routing: RoutingMode = RoutingMode.Hybrid,
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048,
    val topP: Double = 0.9,
)

object AppSettingsStore {
    private val file = Path.of(System.getProperty("user.home"), ".pulse", "settings.properties")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    init { load() }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        scope.launch { persist(next) }
    }

    private fun load() {
        if (!Files.exists(file)) return
        runCatching {
            val p = Properties()
            Files.newInputStream(file).use { p.load(it) }
            _state.value = AppSettings(
                activeModelId = p.getProperty("activeModelId") ?: "qwen2.5-coder:7b",
                routing = runCatching { RoutingMode.valueOf(p.getProperty("routing") ?: "Hybrid") }.getOrDefault(RoutingMode.Hybrid),
                temperature = p.getProperty("temperature")?.toDoubleOrNull() ?: 0.7,
                maxTokens = p.getProperty("maxTokens")?.toIntOrNull() ?: 2048,
                topP = p.getProperty("topP")?.toDoubleOrNull() ?: 0.9,
            )
        }
    }

    private suspend fun persist(s: AppSettings) = withContext(Dispatchers.IO) {
        Files.createDirectories(file.parent)
        val p = Properties()
        p.setProperty("activeModelId", s.activeModelId)
        p.setProperty("routing", s.routing.name)
        p.setProperty("temperature", s.temperature.toString())
        p.setProperty("maxTokens", s.maxTokens.toString())
        p.setProperty("topP", s.topP.toString())
        Files.newOutputStream(file).use { p.store(it, "Pulse app settings") }
    }
}
