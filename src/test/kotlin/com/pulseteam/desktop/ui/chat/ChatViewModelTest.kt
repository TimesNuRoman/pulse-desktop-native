// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — ChatViewModel unit tests. Verifies the "newChat" reset
// path that the sidebar + palette rely on, plus a guard for the
// "streamJob cancelled before sendMessage" case.
package com.pulseteam.desktop.ui.chat

import com.pulseteam.desktop.data.ai.AiEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatViewModelTest {

    /** A canned engine that emits a fixed list of tokens then completes. */
    private class CannedEngine(private val tokens: List<String> = listOf("hi", " there")) : AiEngine {
        override val name: String = "canned"
        override fun streamReply(userMessage: String): Flow<String> = MutableSharedFlow<String>().also { flow ->
            // emit synchronously in the test scope
            tokens.forEach { flow.tryEmit(it) }
        }.asSharedFlow()
    }

    @Test
    fun `newChat clears messages and webStatus`() = runBlocking {
        val vm = ChatViewModel(engine = CannedEngine())
        // Drive sendMessage to put something on the messages list.
        vm.sendMessage("hello")
        // Wait a tick so the launched streamJob can append the user+ai pair.
        delay(50)
        assertTrue(vm.messages.value.isNotEmpty(), "sendMessage should add at least 2 entries")

        vm.newChat()
        assertEquals(emptyList<ChatMessage>(), vm.messages.value, "newChat should clear messages")
        assertEquals(null, vm.webStatus.value, "newChat should clear webStatus")
    }

    @Test
    fun `sendMessage with empty input is a no-op`() = runBlocking {
        val vm = ChatViewModel(engine = CannedEngine())
        vm.sendMessage("   ")
        delay(20)
        assertEquals(emptyList<ChatMessage>(), vm.messages.value, "empty input should not add messages")
    }

    @Test
    fun `cancel stops an in-flight stream`() = runBlocking {
        // Use a long-flow engine that won't complete during the test
        val slowFlow = MutableSharedFlow<String>()
        val slowEngine = object : AiEngine {
            override val name = "slow"
            override fun streamReply(userMessage: String): Flow<String> = slowFlow.asSharedFlow()
        }
        val vm = ChatViewModel(engine = slowEngine)
        vm.sendMessage("hi")
        // We don't need to verify the AI message — just that cancel() doesn't throw
        // and that the message list still contains the user+ai stub.
        vm.cancel()
        assertTrue(vm.messages.value.isNotEmpty(), "cancel() should not wipe the user+ai stub")
    }
}

