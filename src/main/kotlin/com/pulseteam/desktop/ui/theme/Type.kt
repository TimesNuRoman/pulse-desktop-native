// SPDX-License-Identifier: Apache-2.0
// Pulse — typography. Inter for body, JetBrains Mono for code/monospace.
// System-font fallback is acceptable if the bundled fonts are not present.
package com.pulseteam.desktop.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// We don't ship the actual Inter / JetBrains Mono TTF files in this skeleton.
// Compose falls back to the system default; downstream task adds font loading
// via fontDirectory / Font(R.font....) once Roman provides the .ttf assets.
private val uiFamily: FontFamily = FontFamily.Default
private val monoFamily: FontFamily = FontFamily.Monospace

val PulseTypography: Typography = Typography(
    // Display (hero text, big numbers)
    displayLarge = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),

    // Headings
    headlineLarge = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),

    // Title (topbar, list items)
    titleLarge = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),

    // Body
    bodyLarge = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),

    // Labels (button text, chips)
    labelLarge = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = uiFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
    ),
)

/** Monospace text style for code blocks, model names, paths, keyboard hints. */
val MonoStyle: TextStyle = TextStyle(
    fontFamily = monoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    fontStyle = FontStyle.Normal,
)

@Composable
fun rememberMonoStyle(): TextStyle = MonoStyle


