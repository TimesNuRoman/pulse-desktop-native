// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — local AI engine abstraction. Streams a response word-by-
// word so the chat pane shows real token-by-token output, not a static
// text block.
//
// v0.8.0+ has NO MOCK. LlamaEngine is the only AiEngine implementation
// shipped. When llama-server can't start (no model, no runtime, server
// crash), the engine throws a clear, user-readable IllegalStateException
// that ChatViewModel surfaces in the chat bubble. The UI then prompts
// the user to install a model or check the runtime.
package com.pulseteam.desktop.data.ai

import kotlinx.coroutines.flow.Flow

interface AiEngine {
    val name: String
    /**
     * Stream a response to [userMessage]. The returned Flow emits word
     * fragments as they arrive from the underlying model. If the engine
     * can't reach the model (server down, model missing, etc.) it throws
     * an [IllegalStateException] with a user-readable message after the
     * caller has already started collecting.
     */
    fun streamReply(userMessage: String): Flow<String>
}
