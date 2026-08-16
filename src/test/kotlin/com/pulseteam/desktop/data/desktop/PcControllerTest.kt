// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — PcController unit tests. Exercise FakePcController
// to verify the call-recording contract. Robot-backed tests would need
// a real display, so we keep them out of CI and rely on the Fake for
// regression coverage.
package com.pulseteam.desktop.data.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent

class PcControllerTest {

    @Test
    fun `click records x, y, button`() {
        val pc = FakePcController()
        pc.click(100, 200)
        assertEquals(1, pc.calls.size)
        val c = pc.calls[0]
        assertEquals("click", c.op)
        assertEquals(100, c.args["x"])
        assertEquals(200, c.args["y"])
        assertEquals(MouseButton.Left, c.args["button"])
    }

    @Test
    fun `click with right button records the right mask`() {
        val pc = FakePcController()
        pc.click(0, 0, MouseButton.Right)
        assertEquals(MouseButton.Right, pc.calls[0].args["button"])
    }

    @Test
    fun `typeText records press-release per char`() {
        val pc = FakePcController()
        pc.typeText("hi", perCharDelayMs = 0)
        // 'h' = press + release, 'i' = press + release → 4 calls
        assertEquals(4, pc.calls.size)
        assertEquals("press", pc.calls[0].op)
        assertEquals("release", pc.calls[1].op)
        assertEquals("press", pc.calls[2].op)
        assertEquals("release", pc.calls[3].op)
        // Same keycode for both halves of each char
        assertEquals(pc.calls[0].args["code"], pc.calls[1].args["code"])
        assertEquals(pc.calls[2].args["code"], pc.calls[3].args["code"])
    }

    @Test
    fun `pressKey records one press and one release`() {
        val pc = FakePcController()
        pc.pressKey(KeyEvent.VK_ENTER)
        assertEquals(2, pc.calls.size)
        assertEquals("press", pc.calls[0].op)
        assertEquals("release", pc.calls[1].op)
        assertEquals(KeyEvent.VK_ENTER, pc.calls[0].args["code"])
    }

    @Test
    fun `pressHotkey emits chord in correct order`() {
        val pc = FakePcController()
        pc.pressHotkey(listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_C))
        // Expected: press CTRL, press C, release C, release CTRL → 4 calls
        assertEquals(4, pc.calls.size)
        assertEquals("press", pc.calls[0].op)
        assertEquals(KeyEvent.VK_CONTROL, pc.calls[0].args["code"])
        assertEquals("press", pc.calls[1].op)
        assertEquals(KeyEvent.VK_C, pc.calls[1].args["code"])
        assertEquals("release", pc.calls[2].op)
        assertEquals(KeyEvent.VK_C, pc.calls[2].args["code"])
        assertEquals("release", pc.calls[3].op)
        assertEquals(KeyEvent.VK_CONTROL, pc.calls[3].args["code"])
    }

    @Test
    fun `pressHotkey with empty list is a no-op`() {
        val pc = FakePcController()
        pc.pressHotkey(emptyList())
        assertTrue(pc.calls.isEmpty(), "empty hotkey should not record any calls")
    }

    @Test
    fun `isAvailable is true for Fake`() {
        assertTrue(FakePcController().isAvailable())
    }
}
