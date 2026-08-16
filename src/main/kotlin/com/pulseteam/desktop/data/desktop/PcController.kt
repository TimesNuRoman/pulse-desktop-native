// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — PC interaction (mouse + keyboard). Real impl wraps
// java.awt.Robot (zero-dep JDK 17). Fake impl records every call into
// a list for assertions in unit tests.
package com.pulseteam.desktop.data.desktop

import com.pulseteam.desktop.data.log.PulseLogger
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/** Mouse + keyboard abstraction. */
interface PcController {
    fun click(x: Int, y: Int, button: MouseButton = MouseButton.Left)
    fun typeText(text: String, perCharDelayMs: Long = 30)
    fun pressKey(keyCode: Int)
    fun pressHotkey(keyCodes: List<Int>)
    /** True if mouse/keyboard events actually deliver on this OS. */
    fun isAvailable(): Boolean
}

/**
 * Real impl: wraps `java.awt.Robot`. Uses `autoDelay = 40` between
 * auto-generated events so the OS reliably receives each one.
 *
 * **macOS caveat:** without Accessibility permission (TCC prompt) the
 * `mouseMove` / `keyPress` calls silently no-op. Pulse should detect
 * this via `isAvailable()` returning false and show an onboarding hint.
 * (Phase 1 only handles Windows + Linux; macOS onboarding is Phase 2.)
 *
 * **Linux caveat:** Wayland is unsupported (Robot is X11-only). Pulse
 * documents this in the Desktop settings panel.
 */
class RobotPcController : PcController {
    private val robot: Robot? = try {
        Robot().also { it.autoDelay = 40 }
    } catch (t: Throwable) {
        PulseLogger.warn("PcController: Robot unavailable", mapOf("error" to (t.message ?: t::class.java.simpleName)))
        null
    }

    override fun click(x: Int, y: Int, button: MouseButton) {
        val r = requireRobot()
        r.mouseMove(x, y)
        val mask = when (button) {
            MouseButton.Left -> InputEvent.BUTTON1_DOWN_MASK
            MouseButton.Right -> InputEvent.BUTTON3_DOWN_MASK
            MouseButton.Middle -> InputEvent.BUTTON2_DOWN_MASK
        }
        r.mousePress(mask)
        r.mouseRelease(mask)
    }

    override fun typeText(text: String, perCharDelayMs: Long) {
        val r = requireRobot()
        for (ch in text) {
            val code = KeyEvent.getExtendedKeyCodeForChar(ch.code)
            if (code == KeyEvent.CHAR_UNDEFINED.code) {
                // Skip chars we can't represent (emoji, some accented chars).
                // The user gets fewer keystrokes than expected but no exception.
                continue
            }
            r.keyPress(code)
            r.keyRelease(code)
            if (perCharDelayMs > 0) r.delay(perCharDelayMs.toInt())
        }
    }

    override fun pressKey(keyCode: Int) {
        val r = requireRobot()
        r.keyPress(keyCode)
        r.keyRelease(keyCode)
    }

    override fun pressHotkey(keyCodes: List<Int>) {
        if (keyCodes.isEmpty()) return
        val r = requireRobot()
        // Press all but the last, then press+release the last, then
        // release the rest in reverse. This is the order the OS expects
        // for a clean chord (e.g. Ctrl+C → press Ctrl, press+release C,
        // release Ctrl).
        for (i in 0 until keyCodes.size - 1) r.keyPress(keyCodes[i])
        val last = keyCodes.last()
        r.keyPress(last)
        r.keyRelease(last)
        for (i in keyCodes.size - 2 downTo 0) r.keyRelease(keyCodes[i])
    }

    override fun isAvailable(): Boolean = robot != null

    private fun requireRobot(): Robot = robot
        ?: throw IllegalStateException("PcController unavailable: Robot cannot be created (headless or unsupported platform)")
}

/**
 * Test impl. Records every call into [calls] for assertion in unit tests.
 * The "Fake" naming follows the project's convention (Fake* test doubles
 * for interfaces that wrap real OS primitives).
 */
class FakePcController : PcController {
    /** A single recorded call. `op` names the method; `args` captures parameters. */
    data class Call(val op: String, val args: Map<String, Any?> = emptyMap())

    val calls: MutableList<Call> = mutableListOf()

    override fun click(x: Int, y: Int, button: MouseButton) {
        calls += Call("click", mapOf("x" to x, "y" to y, "button" to button))
    }

    override fun typeText(text: String, perCharDelayMs: Long) {
        // Record one Call per character pair (press + release). The
        // exact pair count is asserted in tests so a regression in
        // typeText (e.g. dropping the release) is caught.
        for (ch in text) {
            val code = KeyEvent.getExtendedKeyCodeForChar(ch.code)
            if (code == KeyEvent.CHAR_UNDEFINED.code) continue
            calls += Call("press", mapOf("code" to code, "char" to ch.toString()))
            calls += Call("release", mapOf("code" to code, "char" to ch.toString()))
        }
    }

    override fun pressKey(keyCode: Int) {
        calls += Call("press", mapOf("code" to keyCode))
        calls += Call("release", mapOf("code" to keyCode))
    }

    override fun pressHotkey(keyCodes: List<Int>) {
        if (keyCodes.isEmpty()) return
        for (i in 0 until keyCodes.size - 1) calls += Call("press", mapOf("code" to keyCodes[i]))
        calls += Call("press", mapOf("code" to keyCodes.last()))
        calls += Call("release", mapOf("code" to keyCodes.last()))
        for (i in keyCodes.size - 2 downTo 0) calls += Call("release", mapOf("code" to keyCodes[i]))
    }

    override fun isAvailable(): Boolean = true
}
