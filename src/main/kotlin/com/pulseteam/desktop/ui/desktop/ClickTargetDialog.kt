// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — ClickTargetDialog. Small modal that asks the user for
// the text to click on (e.g. "Submit", "Open File", "Cancel"). Shown
// when the user picks "Кликни: …" from the command palette without a
// target already typed.
package com.pulseteam.desktop.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import com.pulseteam.desktop.ui.theme.PulseColors

/**
 * Tiny modal: "What to click on?" with a single text field. Submit on
 * Enter, cancel on Esc. The user types the button or label they want
 * Pulse to find via OCR + click.
 *
 * @param initial the prefilled target (may be empty).
 * @param onSubmit called with the trimmed, non-empty target.
 * @param onCancel called when the user backs out (Esc / Cancel button / backdrop).
 */
@Composable
fun ClickTargetDialog(
    initial: String = "",
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PulseColors.Drop)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Escape -> { onCancel(); true }
                        Key.Enter -> {
                            val trimmed = value.trim()
                            if (trimmed.isNotEmpty()) onSubmit(trimmed)
                            else onCancel()
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        // Backdrop click = cancel.
        Box(modifier = Modifier.fillMaxSize().clickable { onCancel() })

        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 520.dp)
                .background(PulseColors.Bg2, RectangleShape)
                .border(1.dp, PulseColors.BorderStrong, RectangleShape)
                .padding(20.dp),
        ) {
            Text(
                "Click on what?",
                color = PulseColors.FgBright,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Type the button or label to find on screen. Pulse will capture, OCR, then ask for confirmation before clicking.",
                color = PulseColors.FgDim,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = PulseColors.FgBright, fontSize = 14.sp),
                cursorBrush = SolidColor(PulseColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(PulseColors.BgInput, RectangleShape)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .focusRequester(focus),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Multi-word targets work: \"Open File\", \"Sign in\".",
                color = PulseColors.FgDisabled,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DialogButton("Cancel", primary = false, onClick = onCancel)
                Spacer(Modifier.width(8.dp))
                DialogButton(
                    text = "Find",
                    primary = true,
                    onClick = {
                        val trimmed = value.trim()
                        if (trimmed.isNotEmpty()) onSubmit(trimmed) else onCancel()
                    },
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (primary) PulseColors.Accent else PulseColors.Bg3
    val fg = if (primary) PulseColors.Bg else PulseColors.Fg
    Box(
        modifier = Modifier
            .background(bg, RectangleShape)
            .border(1.dp, PulseColors.Border, RectangleShape)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
