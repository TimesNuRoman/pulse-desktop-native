// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — ConfirmActionDialog. Modal that appears when the
// SafetyGate has a pending action. Shows the action summary, a preview
// screenshot (if available), and Confirm / Cancel buttons. Square edges,
// Tokyo Night palette, no emoji.
package com.pulseteam.desktop.ui.desktop

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.pulseteam.desktop.data.desktop.PendingAction
import com.pulseteam.desktop.ui.theme.PulseColors
import javax.imageio.ImageIO

/**
 * Modal dialog shown when a host-PC action is awaiting user approval.
 * Backdrop click = cancel. Esc = cancel. Enter = confirm.
 *
 * @param pending The action that's waiting for approval.
 * @param onConfirm Called when the user clicks Confirm or hits Enter.
 * @param onCancel Called when the user clicks Cancel, hits Esc, or clicks the backdrop.
 */
@Composable
fun ConfirmActionDialog(
    pending: PendingAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PulseColors.Drop)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Escape -> { onCancel(); true }
                        Key.Enter -> { onConfirm(); true }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        // Backdrop click → cancel
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onCancel() },
        )

        Column(
            modifier = Modifier
                .widthIn(min = 480.dp, max = 560.dp)
                .heightIn(max = 640.dp)
                .background(PulseColors.Bg2, RectangleShape)
                .border(1.dp, PulseColors.BorderStrong, RectangleShape)
                .padding(20.dp),
        ) {
            // Title
            Text(
                "Confirm desktop action",
                color = PulseColors.FgBright,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                pending.summary,
                color = PulseColors.Fg,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))

            // Preview screenshot, if available
            val previewPath = pending.screenshotPath
            if (previewPath != null && previewPath.exists()) {
                val bitmap: androidx.compose.ui.graphics.ImageBitmap? =
                    androidx.compose.runtime.remember(previewPath.absolutePath) {
                        try {
                            val bytes = previewPath.readBytes()
                            org.jetbrains.skia.Image.makeFromEncoded(bytes).asImageBitmap()
                        } catch (_: Throwable) {
                            null
                        }
                    }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "screen preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .border(1.dp, PulseColors.Border, RectangleShape),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            // Buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DialogButton("Cancel", primary = false, onClick = onCancel)
                Spacer(Modifier.width(8.dp))
                DialogButton("Confirm", primary = true, onClick = onConfirm)
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
