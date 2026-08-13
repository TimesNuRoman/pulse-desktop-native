// SPDX-License-Identifier: Apache-2.0
// Pulse — root theme. Dark only, square edges, custom colors mapped to M3 ColorScheme.
//
// Hard rules (per spec):
//   - NO RoundedCornerShape anywhere
//   - NO lightColorScheme() — darkColorScheme() only
//   - All shapes are RectangleShape (square edges)
package com.pulseteam.desktop.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Square shapes — global rule. Every Material3 surface, button, card uses
 * RoundedCornerShape(0.dp) to keep edges sharp (zero corner radius).
 */
val PulseShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

/** Map Pulse palette onto the M3 dark color scheme. */
private val PulseColorScheme = darkColorScheme(
    primary = PulseColors.Accent,
    onPrimary = PulseColors.Bg,
    primaryContainer = PulseColors.AccentSoft,
    onPrimaryContainer = PulseColors.Accent,

    secondary = PulseColors.Accent2,
    onSecondary = PulseColors.Bg,
    secondaryContainer = PulseColors.Bg3,
    onSecondaryContainer = PulseColors.Fg,

    tertiary = PulseColors.Accent3,
    onTertiary = PulseColors.Bg,

    background = PulseColors.Bg,
    onBackground = PulseColors.Fg,
    surface = PulseColors.Bg,
    onSurface = PulseColors.Fg,
    surfaceVariant = PulseColors.Bg2,
    onSurfaceVariant = PulseColors.FgDim,

    surfaceTint = PulseColors.Accent,
    inverseSurface = PulseColors.Fg,
    inverseOnSurface = PulseColors.Bg,
    inversePrimary = PulseColors.Bg,

    error = PulseColors.Error,
    onError = PulseColors.Bg,
    errorContainer = PulseColors.Bg3,
    onErrorContainer = PulseColors.Error,

    outline = PulseColors.Border,
    outlineVariant = PulseColors.BorderStrong,
    scrim = PulseColors.Drop,
)

@Composable
fun PulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PulseColorScheme,
        typography = PulseTypography,
        shapes = PulseShapes,
        content = content,
    )
}

/** Fallback transparent color used in places that need a "no fill" stub. */
val NoColor: Color = Color.Transparent


