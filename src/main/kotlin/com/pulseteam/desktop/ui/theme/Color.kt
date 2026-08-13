// SPDX-License-Identifier: Apache-2.0
// Pulse — Tokyo Night palette. Single source of truth for all UI colors.
// Hard rule: dark only, no light theme, no "prefers-color-scheme: light" handling.
package com.pulseteam.desktop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Tokyo Night (lightweight palette variant).
 * Hex values match the canonical Tokyo Night theme used across Pulse products.
 */
object PulseColors {
    // Backgrounds
    val Bg = Color(0xFF1A1B26)         // root canvas
    val Bg2 = Color(0xFF24283B)        // panels (sidebar, right, topbar/save bar)
    val Bg3 = Color(0xFF2A2E3F)        // hover/selected rows
    val BgHover = Color(0xFF2F334D)    // subtle hover
    val BgInput = Color(0xFF1F2335)    // input fields

    // Foregrounds
    val Fg = Color(0xFFC0CAF5)         // primary text
    val FgBright = Color(0xFFD6DDF4)    // headings
    val FgDim = Color(0xFF565F89)      // secondary, hints
    val FgDisabled = Color(0xFF414868) // disabled controls

    // Borders
    val Border = Color(0xFF2A2F45)        // default 1dp
    val BorderStrong = Color(0xFF3B4261)  // emphasised (input, modal, palette)
    val BorderAccent = Color(0xFFFF9E64)  // focus / active

    // Brand
    val Accent = Color(0xFFFF9E64)      // warm orange (Pulse primary)
    val AccentSoft = Color(0x33FF9E64)  // 20% alpha for soft fills
    val Accent2 = Color(0xFFBB9AF7)     // purple
    val Accent3 = Color(0xFF7AA2F7)     // blue

    // Semantic
    val Green = Color(0xFF9ECE6A)       // ready, done
    val Warn = Color(0xFFE0AF68)        // warn, today
    val Error = Color(0xFFF7768E)       // error, danger
    val Cyan = Color(0xFF7DCFFF)        // info
    val Magenta = Color(0xFFBB9AF7)     // alt
    val Yellow = Color(0xFFE0AF68)      // alt

    // Code block
    val CodeBg = Color(0xFF16161E)
    val CodeFg = Color(0xFF9ECE6A)

    // Drop
    val Drop = Color(0x66121326)
}


