// SPDX-License-Identifier: Apache-2.0
// Pulse — ChatViewModel. Holds messages + handles streaming from the AI engine.
// MutableStateFlow is thread-safe so the engine coroutine (on Dispatchers.Default)
// can append tokens without juggling dispatchers.
//
// v0.2.1: after each AI stream completes, scans the response for [[note links]]
// and emits them via onNotesCreated so the caller (Main.kt) can write real
// notes to SQLite via NoteRepository.
package com.pulseteam.desktop.ui.chat

import com.pulseteam.desktop.data.ai.AiEngine
import com.pulseteam.desktop.data.ai.LocalMockEngine
import com.pulseteam.desktop.data.notes.NoteLink
import com.pulseteam.desktop.data.notes.NoteLinkParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val engine: AiEngine = LocalMockEngine(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Called with parsed [[note links]] from each completed AI response. */
    private val onNotesCreated: (List<NoteLink>) -> Unit = {},
) {
    private val _messages: MutableStateFlow<List<ChatMessage>> = MutableStateFlow(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

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
                engine.streamReply(trimmed).collect { chunk ->
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
