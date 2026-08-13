// SPDX-License-Identifier: Apache-2.0
// Pulse — ChatViewModel. Holds messages + handles streaming from the AI engine.
// MutableStateFlow is thread-safe so the engine coroutine (on Dispatchers.Default)
// can append tokens without juggling dispatchers.
//
// v0.2.1: after each AI stream completes, scans the response for [[note links]]
// and emits them via onNotesCreated so the caller (Main.kt) can write real
// notes to SQLite via NoteRepository.
//
// v0.7.0: optional WebSearch. When isWebSearchEnabled() is true at send time,
// we run a DuckDuckGo HTML search and prepend the formatted results to the
// user prompt before streamReply. The webStatus StateFlow is exposed so the
// composer can show "Web: 5 results" or "Searching…" without a separate
// callback.
package com.pulseteam.desktop.ui.chat

import com.pulseteam.desktop.data.ai.AiEngine
import com.pulseteam.desktop.data.ai.LocalMockEngine
import com.pulseteam.desktop.data.notes.NoteLink
import com.pulseteam.desktop.data.notes.NoteLinkParser
import com.pulseteam.desktop.data.web.WebSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val engine: AiEngine = LocalMockEngine(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Optional web search provider. Null disables web search entirely. */
    private val webSearch: WebSearch? = null,
    /** Read fresh at send time so UI toggle state stays in sync. */
    private val isWebSearchEnabled: () -> Boolean = { false },
    /** Called with parsed [[note links]] from each completed AI response. */
    private val onNotesCreated: (List<NoteLink>) -> Unit = {},
) {
    private val _messages: MutableStateFlow<List<ChatMessage>> = MutableStateFlow(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** One-shot status string the UI can render under the composer. */
    private val _webStatus: MutableStateFlow<String?> = MutableStateFlow(null)
    val webStatus: StateFlow<String?> = _webStatus.asStateFlow()

    private var streamJob: Job? = null

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val userMsg = ChatMessage(
            id = "u-${System.currentTimeMillis()}",
            from = "user",
            text = trimmed,
        )
        val aiMsgId = "a-${System.currentTimeMillis()}"
        val aiMsg = ChatMessage(aiMsgId, "ai", "")

        _messages.update { it + listOf(userMsg, aiMsg) }

        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                // Web search: optional, runs only if both a WebSearch is wired
                // AND the user has the toggle ON at send time. We augment the
                // prompt in place — the displayed user message stays the
                // original text (so the chat log doesn't get noisy).
                var prompt = trimmed
                if (webSearch != null && isWebSearchEnabled()) {
                    _webStatus.value = "Searching the web…"
                    val results = webSearch.search(trimmed)
                    if (results.isNotEmpty()) {
                        val ctx = webSearch.formatForLlm(results, trimmed)
                        prompt = "$ctx\n\n$trimmed"
                        _webStatus.value = "Web: ${results.size} result${if (results.size == 1) "" else "s"}"
                    } else {
                        _webStatus.value = "Web: no results"
                    }
                }

                engine.streamReply(prompt).collect { chunk ->
                    _messages.update { current ->
                        current.map { msg -> if (msg.id == aiMsgId) msg.copy(text = msg.text + chunk) else msg }
                    }
                }
                // After the stream finishes, scan the AI message for [[note links]]
                // and hand them to the caller. The caller (Main.kt) is responsible
                // for actually writing them to SQLite + refreshing the sidebar.
                val finalText = _messages.value.firstOrNull { it.id == aiMsgId }?.text.orEmpty()
                val links = NoteLinkParser.extract(finalText)
                if (links.isNotEmpty()) onNotesCreated(links)

                // Fade the web status after a few seconds so the composer
                // doesn't stay cluttered between messages.
                if (_webStatus.value != null) {
                    delay(5_000)
                    _webStatus.value = null
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Swallow: a newer user message cancelled us.
            }
        }
    }

    fun cancel() {
        streamJob?.cancel()
        streamJob = null
    }

    fun shutdown() {
        cancel()
        scope.cancel()
    }
}
