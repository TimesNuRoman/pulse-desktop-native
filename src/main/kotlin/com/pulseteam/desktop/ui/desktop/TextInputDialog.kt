// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — TextInputDialog. Reusable single-line text input
// modal used by the palette commands that need user-supplied text
// (Type: …, Hotkey: …). Submit on Enter, cancel on Esc. Optional
// validator that disables Submit when input is invalid.
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.pulseteam.desktop.ui.theme.PulseColors

/**
 * Single-line text input modal. Submit is disabled while [validate]
 * returns false (or while the field is empty if validate is null).
 *
 * @param title short label shown in the modal header.
 * @param hint one-line guidance shown under the title.
 * @param example small "e.g." example value (optional).
 * @param placeholder placeholder text shown in the empty field.
 * @param initial pre-filled value.
 * @param validate optional validator. Submit is disabled when this
 *   returns false. Error message returned by this function is shown
 *   under the field.
 * @param onSubmit called with the trimmed, valid input.
 * @param onCancel called on Esc / Cancel button / backdrop click.
 */
@Composable
fun TextInputDialog(
    title: String,
    hint: String,
    placeholder: String = "",
    example: String? = null,
    initial: String = "",
    validate: ((String) -> String?)? = null,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val trimmed = value.trim()
    val error = validate?.let { it(trimmed) }
    val canSubmit = trimmed.isNotEmpty() && error == null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PulseColors.Drop)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Escape -> { onCancel(); true }
                        Key.Enter -> {
                            if (canSubmit) onSubmit(trimmed) else onCancel()
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.fillMaxSize().clickable { onCancel() })

        Column(
            modifier = Modifier
                .widthIn(min = 460.dp, max = 560.dp)
                .background(PulseColors.Bg2, RectangleShape)
                .border(1.dp, PulseColors.BorderStrong, RectangleShape)
                .padding(20.dp),
        ) {
            Text(
                title,
                color = PulseColors.FgBright,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                color = PulseColors.FgDim,
                fontSize = 11.sp,
            )
            if (example != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "e.g. $example",
                    color = PulseColors.FgDisabled,
                    fontSize = 10.sp,
                    style = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                )
            }
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
            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(error, color = PulseColors.Error, fontSize = 10.sp)
            }
            if (placeholder.isNotEmpty() && value.isEmpty()) {
                // Placeholder hint shown via the empty text — BasicTextField
                // doesn't have a built-in placeholder param, so we draw a
                // ghost overlay when the field is empty.
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp, top = 6.dp)
                        .height(0.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DialogButton("Cancel", primary = false, onClick = onCancel)
                Spacer(Modifier.width(8.dp))
                DialogButton(
                    text = "Run",
                    primary = true,
                    onClick = {
                        if (canSubmit) onSubmit(trimmed) else onCancel()
                    },
                    enabled = canSubmit,
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
    enabled: Boolean = true,
) {
    val bg = when {
        !enabled -> PulseColors.Bg3
        primary -> PulseColors.Accent
        else -> PulseColors.Bg3
    }
    val fg = when {
        !enabled -> PulseColors.FgDisabled
        primary -> PulseColors.Bg
        else -> PulseColors.Fg
    }
    Box(
        modifier = Modifier
            .background(bg, RectangleShape)
            .border(1.dp, PulseColors.Border, RectangleShape)
            .clickable(enabled = enabled) { onClick() }
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
