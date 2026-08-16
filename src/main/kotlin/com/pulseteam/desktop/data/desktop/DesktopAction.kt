// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — DesktopAction. Sealed class of host-PC operations that
// the user can authorise through the SafetyGate. Screenshot + read-text
// are passive (no risk, no confirmation). Click/Type/Key/Hotkey are
// active and always need user approval in Phase 1.
package com.pulseteam.desktop.data.desktop

import java.io.File

/** Which mouse button a Click action targets. */
enum class MouseButton { Left, Right, Middle }

/**
 * The set of operations Pulse can perform on the host PC. Coordinates are
 * **logical pixels** (CSS-px, NOT device pixels) — `java.awt.Robot` handles
 * DPI scaling since JDK 9, so callers should think in "what the user sees".
 */
sealed class DesktopAction {
    /** Move + click at (x, y) on the primary screen. */
    data class Click(
        val x: Int,
        val y: Int,
        val button: MouseButton = MouseButton.Left,
    ) : DesktopAction()

    /** Type a string char-by-char into the currently-focused control. */
    data class Type(val text: String) : DesktopAction()

    /** Press a single key (use VK_* constants from java.awt.event.KeyEvent). */
    data class Key(val keyCode: Int) : DesktopAction()

    /**
     * Press a key combo (e.g. Ctrl+C = [VK_CONTROL, VK_C]). Impl releases
     * keys in reverse order so the OS sees a clean chord.
     */
    data class Hotkey(val keyCodes: List<Int>) : DesktopAction()

    /**
     * Capture full screen to [dest]. Passive — does NOT route through the
     * SafetyGate because it doesn't move the cursor or simulate input.
     */
    data class Screenshot(val dest: File) : DesktopAction()
}
