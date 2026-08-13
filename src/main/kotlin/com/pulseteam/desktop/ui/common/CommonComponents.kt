// SPDX-License-Identifier: Apache-2.0
// Pulse — common UI primitives used across screens. All shapes are RectangleShape
// (square edges, no rounded corners). Tokyo Night palette via PulseColors.
package com.pulseteam.desktop.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.pulseteam.desktop.ui.theme.PulseColors
import com.pulseteam.desktop.ui.theme.MonoStyle

/**
 * Status dot — a small square (3dp × 3dp) used in topbar/right panel to
 * indicate readiness, busy, or error. Square not round (per Pulse rules).
 */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(
        modifier = Modifier
            .width(size)
            .height(size)
            .background(color, RectangleShape),
    )
}

/** Single-line status item used in the bottom status bar. */
@Composable
fun StatusItem(
    text: String,
    leading: (@Composable () -> Unit)? = null,
    color: Color = PulseColors.FgDim,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
        )
    }
}

/** Vertical separator used between status items. */
@Composable
fun StatusSeparator() {
    Text(
        text = "·",
        color = PulseColors.FgDisabled,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** 1dp horizontal divider. */
@Composable
fun HDivider(color: Color = PulseColors.Border) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/** 1dp vertical divider. */
@Composable
fun VDivider(color: Color = PulseColors.Border) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .background(color),
    )
}

/** Keyboard-shortcut pill — small monospace badge with border. */
@Composable
fun Kbd(text: String) {
    Text(
        text = text,
        color = PulseColors.FgDim,
        fontSize = 10.sp,
        modifier = Modifier
            .border(1.dp, PulseColors.Border, RectangleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Monospace model name display, e.g. "qwen2.5-coder:7b". */
@Composable
fun MonoLabel(text: String, color: Color = PulseColors.Fg) {
    Text(
        text = text,
        color = color,
        style = MonoStyle.copy(fontWeight = FontWeight.Medium),
    )
}



