// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — HotkeyParser. Parses user-friendly hotkey strings
// (e.g. "Ctrl+Shift+S", "alt+f4", "Enter") into the List<Int> of
// VK codes that PcController.pressHotkey expects.
package com.pulseteam.desktop.data.desktop

import java.awt.event.KeyEvent
import kotlin.math.abs

/**
 * Token names that map directly to a KeyEvent.VK_* constant. Kept
 * lowercase; the parser lowercases user input before lookup.
 *
 * Multi-char names (like "pageup") are accepted to match common user
 * expectations; we then convert to the camelCase VK constant.
 */
private val NAMED_KEYS: Map<String, Int> = mapOf(
    // Letters
    *('A'..'Z').map { it.lowercase() to (KeyEvent.VK_A + (it.code - 'A'.code)) }.toTypedArray(),
    // Digits
    *('0'..'9').map { it.toString() to (KeyEvent.VK_0 + (it.code - '0'.code)) }.toTypedArray(),
    // Function keys
    *((1..12).map { "f$it" to (KeyEvent.VK_F1 + (it - 1)) }).toTypedArray(),
    // Common special keys
    "enter" to KeyEvent.VK_ENTER,
    "return" to KeyEvent.VK_ENTER,
    "tab" to KeyEvent.VK_TAB,
    "space" to KeyEvent.VK_SPACE,
    "esc" to KeyEvent.VK_ESCAPE,
    "escape" to KeyEvent.VK_ESCAPE,
    "backspace" to KeyEvent.VK_BACK_SPACE,
    "delete" to KeyEvent.VK_DELETE,
    "del" to KeyEvent.VK_DELETE,
    "home" to KeyEvent.VK_HOME,
    "end" to KeyEvent.VK_END,
    "pageup" to KeyEvent.VK_PAGE_UP,
    "pgup" to KeyEvent.VK_PAGE_UP,
    "pagedown" to KeyEvent.VK_PAGE_DOWN,
    "pgdn" to KeyEvent.VK_PAGE_DOWN,
    "pgdown" to KeyEvent.VK_PAGE_DOWN,
    "up" to KeyEvent.VK_UP,
    "down" to KeyEvent.VK_DOWN,
    "left" to KeyEvent.VK_LEFT,
    "right" to KeyEvent.VK_RIGHT,
    // Punctuation (US keyboard layout; extended layout users may need to
    // press the symbol directly via the chat composer instead)
    "minus" to KeyEvent.VK_MINUS,
    "equals" to KeyEvent.VK_EQUALS,
    "comma" to KeyEvent.VK_COMMA,
    "period" to KeyEvent.VK_PERIOD,
    "slash" to KeyEvent.VK_SLASH,
    "backslash" to KeyEvent.VK_BACK_SLASH,
    "semicolon" to KeyEvent.VK_SEMICOLON,
    "quote" to KeyEvent.VK_QUOTE,
    "backtick" to KeyEvent.VK_BACK_QUOTE,
    "bracketleft" to KeyEvent.VK_OPEN_BRACKET,
    "bracketright" to KeyEvent.VK_CLOSE_BRACKET,
)

private val MODIFIER_KEYS: Map<String, Int> = mapOf(
    "ctrl" to KeyEvent.VK_CONTROL,
    "control" to KeyEvent.VK_CONTROL,
    "shift" to KeyEvent.VK_SHIFT,
    "alt" to KeyEvent.VK_ALT,
    "meta" to KeyEvent.VK_META,
    "cmd" to KeyEvent.VK_META,
    "win" to KeyEvent.VK_META,
)

/**
 * Parse a hotkey string into an ordered list of VK codes (modifiers first,
 * the actual key last). Returns null on any unknown token. The returned
 * list is in press order; the executor releases in reverse.
 *
 * Examples:
 *   "Ctrl+Shift+S"   → [VK_CONTROL, VK_SHIFT, VK_S]
 *   "alt+f4"          → [VK_ALT, VK_F4]
 *   "Enter"           → [VK_ENTER]
 *   "ctrl + c"        → [VK_CONTROL, VK_C]  (whitespace tolerated)
 */
fun parseHotkey(input: String): List<Int>? {
    val parts = input.split('+').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val codes = mutableListOf<Int>()
    for (p in parts) {
        val code = NAMED_KEYS[p] ?: MODIFIER_KEYS[p] ?: return null
        codes += code
    }
    // Reject if it ended up only as modifiers (no real key). The user
    // pressing "Ctrl" alone doesn't make sense.
    if (codes.all { MODIFIER_KEYS.values.contains(it) }) return null
    // Reject if a modifier appears in the middle of a chord. This is
    // not strictly wrong but is unusual; we normalise by moving all
    // modifiers to the front to match what users expect (and what the
    // executor expects).
    val (mods, rest) = codes.partition { MODIFIER_KEYS.values.contains(it) }
    return (mods + rest).distinct()
}

/**
 * Render a list of VK codes back to a human-readable string, e.g. for
 * the SafetyGate confirm dialog. Used by DesktopController when building
 * the pending action summary.
 *
 * Names are picked from a preferred short list rather than the
 * aliases map, so "Ctrl" always renders as "Ctrl" (not "Control") and
 * "Meta" as "Meta" (not "Cmd" or "Win").
 */
fun renderHotkey(codes: List<Int>): String {
    if (codes.isEmpty()) return "?"
    val parts = mutableListOf<String>()
    for (c in codes) {
        val part = when (c) {
            KeyEvent.VK_CONTROL -> "Ctrl"
            KeyEvent.VK_SHIFT -> "Shift"
            KeyEvent.VK_ALT -> "Alt"
            KeyEvent.VK_META -> "Meta"
            KeyEvent.VK_ENTER -> "Enter"
            KeyEvent.VK_TAB -> "Tab"
            KeyEvent.VK_SPACE -> "Space"
            KeyEvent.VK_ESCAPE -> "Esc"
            KeyEvent.VK_BACK_SPACE -> "Backspace"
            KeyEvent.VK_DELETE -> "Delete"
            KeyEvent.VK_HOME -> "Home"
            KeyEvent.VK_END -> "End"
            KeyEvent.VK_PAGE_UP -> "PageUp"
            KeyEvent.VK_PAGE_DOWN -> "PageDown"
            KeyEvent.VK_UP -> "Up"
            KeyEvent.VK_DOWN -> "Down"
            KeyEvent.VK_LEFT -> "Left"
            KeyEvent.VK_RIGHT -> "Right"
            in KeyEvent.VK_A..KeyEvent.VK_Z -> ('A' + (c - KeyEvent.VK_A)).toString()
            in KeyEvent.VK_0..KeyEvent.VK_9 -> ('0' + (c - KeyEvent.VK_0)).toString()
            in KeyEvent.VK_F1..KeyEvent.VK_F12 -> "F${c - KeyEvent.VK_F1 + 1}"
            else -> "0x${c.toString(16)}"
        }
        parts += part
    }
    return parts.joinToString("+")
}

/**
 * Check that a string is a recognisable hotkey without throwing. Used by
 * the inline hotkey dialog to enable/disable the Submit button.
 */
fun isValidHotkey(input: String): Boolean = parseHotkey(input) != null

@Suppress("unused")
private fun Char.dummy() = abs(code)
