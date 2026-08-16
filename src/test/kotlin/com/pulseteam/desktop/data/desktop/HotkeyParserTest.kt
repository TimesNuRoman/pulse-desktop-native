// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — HotkeyParser unit tests. Cover the chord → VK code
// conversion, modifier normalisation, and round-trip via renderHotkey.
package com.pulseteam.desktop.data.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent

class HotkeyParserTest {

    @Test
    fun `parseHotkey accepts single named key`() {
        val codes = parseHotkey("Enter")
        assertNotNull(codes)
        assertEquals(listOf(KeyEvent.VK_ENTER), codes)
    }

    @Test
    fun `parseHotkey accepts letter`() {
        val codes = parseHotkey("a")
        assertNotNull(codes)
        assertEquals(listOf(KeyEvent.VK_A), codes)
    }

    @Test
    fun `parseHotkey accepts Ctrl+Shift+S with modifiers first`() {
        val codes = parseHotkey("Ctrl+Shift+S")
        assertNotNull(codes)
        // Expected order: Ctrl, Shift, S.
        assertEquals(listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_S), codes)
    }

    @Test
    fun `parseHotkey normalises modifiers to the front even when chord is weird`() {
        val codes = parseHotkey("S+Ctrl")
        assertNotNull(codes)
        // Even if user wrote key first, parser moves modifiers to the front.
        assertEquals(listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_S), codes)
    }

    @Test
    fun `parseHotkey handles whitespace and mixed case`() {
        val codes = parseHotkey("  ctrl  +  shift  +  S  ")
        assertNotNull(codes)
        assertEquals(listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_S), codes)
    }

    @Test
    fun `parseHotkey supports f-keys and function-named aliases`() {
        assertEquals(listOf(KeyEvent.VK_F4), parseHotkey("F4"))
        assertEquals(listOf(KeyEvent.VK_ESCAPE), parseHotkey("esc"))
        assertEquals(listOf(KeyEvent.VK_ESCAPE), parseHotkey("Escape"))
        assertEquals(listOf(KeyEvent.VK_TAB), parseHotkey("Tab"))
    }

    @Test
    fun `parseHotkey aliases alt-f4 to alt+F4`() {
        val codes = parseHotkey("alt+f4")
        assertNotNull(codes)
        assertEquals(listOf(KeyEvent.VK_ALT, KeyEvent.VK_F4), codes)
    }

    @Test
    fun `parseHotkey accepts cmd and meta as META modifier`() {
        val codes = parseHotkey("cmd+Q")
        assertNotNull(codes)
        assertEquals(listOf(KeyEvent.VK_META, KeyEvent.VK_Q), codes)
    }

    @Test
    fun `parseHotkey returns null on empty input`() {
        assertNull(parseHotkey(""))
        assertNull(parseHotkey("   "))
    }

    @Test
    fun `parseHotkey returns null on unknown token`() {
        assertNull(parseHotkey("Ctrl+Bogus"))
        assertNull(parseHotkey("Zog"))
    }

    @Test
    fun `parseHotkey rejects modifier-only input`() {
        assertNull(parseHotkey("Ctrl"))
        assertNull(parseHotkey("Ctrl+Shift"))
    }

    @Test
    fun `renderHotkey round-trips common chords`() {
        val cases = listOf(
            listOf(KeyEvent.VK_ENTER) to "Enter",
            listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_C) to "Ctrl+C",
            listOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_S) to "Ctrl+Shift+S",
            listOf(KeyEvent.VK_ALT, KeyEvent.VK_F4) to "Alt+F4",
            listOf(KeyEvent.VK_META, KeyEvent.VK_Q) to "Meta+Q",
        )
        for ((codes, expected) in cases) {
            assertEquals(expected, renderHotkey(codes))
        }
    }

    @Test
    fun `isValidHotkey mirrors parseHotkey result`() {
        assertTrue(isValidHotkey("Ctrl+Shift+S"))
        assertTrue(isValidHotkey("enter"))
        assertTrue(!isValidHotkey("Ctrl"))
        assertTrue(!isValidHotkey(""))
    }
}
