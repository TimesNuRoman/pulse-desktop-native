// SPDX-License-Identifier: Apache-2.0
// Pulse — local AI engine. Streams a response word-by-word so the chat
// pane shows real token-by-token output, not a static text block.
//
// v0.1.0 ships a rule-based mock. When api.ownlocalml.com is wired up,
// replace LocalMockEngine with HttpAiEngine that does an SSE stream to
// /v1/chat/completions.
package com.pulseteam.desktop.data.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface AiEngine {
    val name: String
    fun streamReply(userMessage: String): Flow<String>
}

/** Rule-based local engine. Picks a canned answer by keyword; streams it word-by-word. */
class LocalMockEngine : AiEngine {
    override val name: String = "local-mock"

    override fun streamReply(userMessage: String): Flow<String> = flow {
        val response = chooseResponse(userMessage)
        // Stream word-by-word, 18ms per word (~55 tok/s, similar to a slow local model)
        val tokens = response.split(" ")
        for ((i, w) in tokens.withIndex()) {
            emit(if (i == 0) w else " $w")
            delay(18L)
        }
    }

    private fun chooseResponse(userMessage: String): String {
        val q = userMessage.lowercase().trim()
        return when {
            q.isEmpty() -> "Send something and I'll riff on it."

            "sync" in q && ("encrypt" in q || "e2e" in q || "secure" in q || "private" in q) ->
                "Yes. Sync uses AES-256-GCM with a key derived from your password via scrypt(N=2^15, r=8, p=1). The server only sees ciphertext + a 12-byte nonce + the 16-byte tag. Your password never leaves this device.\n\nDropping two notes from this answer: [[Sync threat model|server stores ciphertext + nonce + tag, nothing else]] and [[Key derivation|scrypt with N=2^15, r=8, p=1]]."

            "backlink" in q || "[[" in userMessage ->
                "Backlinks are automatic. Type [[ in any note to link another note, and the linked note will list every reference in its Backlinks panel on the right. You can also use @ to mention a note without creating a link.\n\nGenerated: [[Backlinks 101|every [[link]] shows up in the target's Backlinks panel]] and [[Note syntax|use [[double brackets]] to link, @ to mention]]."

            "model" in q && ("switch" in q || "change" in q || "pick" in q) ->
                "Open Settings (Cmd+,), Models tab, and pick another one from the grid. The active model is shown with a checkmark. Quantizations (Q4_K_M, Q5_K_M) and disk size are listed under each name.\n\nSaving: [[Model switcher|Open Cmd+, to swap between qwen2.5-coder:7b, llama3.1:8b, mistral-nemo:12b]]."

            "voice" in q || "transcrib" in q || "mic" in q ->
                "Click the mic icon in the composer, or press Cmd+Shift+V to toggle voice. Audio is captured locally, transcribed by Whisper, and the text drops into the composer without leaving the device.\n\nCreated: [[Voice capture|Cmd+Shift+V toggles mic, audio stays on device]] and [[Whisper local|transcription runs against the local Whisper model]]."

            "note" in q && ("create" in q || "new" in q || "make" in q) ->
                "Press Cmd+Shift+N to create a new note, or use the 'New note' card at the top of the sidebar. Notes support Markdown, [[backlinks]], and @mentions. Search across all notes with Cmd+K.\n\nStashing: [[Note keyboard shortcuts|Cmd+Shift+N new, Cmd+K search, Cmd+P open]]."

            "search" in q || "find" in q || "where" in q ->
                "Press Cmd+K to open the command palette. It searches actions (New chat, Switch model, Toggle voice) and your recent notes. Type a few letters, arrow keys to navigate, Enter to run.\n\nSaved: [[Cmd+K tips|arrow keys + enter, esc to close, type to filter]]."

            ("shortcut" in q || "hotkey" in q || "keybind" in q) ->
                "Cmd+K = Command palette. Cmd+, = Settings. Cmd+N = New chat. Cmd+Shift+N = New note. Cmd+S = Sync now. Cmd+Shift+M = Switch model. Cmd+Shift+V = Voice. Cmd+Shift+W = Web search. Cmd=B = Toggle sidebar. Cmd+J = Toggle right panel.\n\nFiled: [[Hotkey cheat sheet|all single-letter shortcuts on the Cmd row, modifiers for the rest]]."

            "local" in q && ("first" in q || "private" in q) ->
                "Pulse keeps everything on this device by default. Notes, chats, model weights, and embeddings live in your home directory. Sync is opt-in and end-to-end encrypted — the server is just dumb storage for ciphertext.\n\nNew: [[Local-first contract|on by default, opt-in sync, no telemetry]] and [[Storage locations|~/.pulse/notes.db, ~/.pulse/models/]]."

            "what" in q && "pulse" in q ->
                "Pulse is a local-first notes + chat + AI workspace. One window, three panels: notes on the left, chat in the middle, context on the right. Everything is Markdown, everything links, everything is searchable. No account. No telemetry.\n\nCaptured: [[Pulse at a glance|notes + chat + local AI in one window]]."

            "who" in q && "are you" in q || "what are you" in q ->
                "I'm the local assistant wired into Pulse. Right now I'm running on the local mock engine — the real model is loading in the background. Same UX, but the answers will get sharper once qwen2.5-coder:7b is fully resident."

            "thanks" in q || "thank you" in q || "spasibo" in q ->
                "Anytime. Ping me when you need a hand."

            "hello" in q || "hi" in q || "hey" in q || "yo" in q ->
                "Hey. Pulse is local-first — your notes, chats, and model stay on this device. Ask me about sync, backlinks, voice, or shortcuts. Cmd+K is the fastest way around."

            else ->
                "Got it: \"${userMessage.trim().take(120)}\". I'm running on the local mock engine while the real backend warms up. Try asking about sync, backlinks, voice, or shortcuts — Cmd+K opens the command palette.\n\nWrote: [[AI response rules|recognise [[double-bracket]] titles and create one note per link]]."
        }
    }
}
