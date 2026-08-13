// SPDX-License-Identifier: Apache-2.0
// Pulse — Password unlock dialog. Shown once per session, right after login.
// The password derives the sync key via scrypt; it is the ONLY thing that
// can decrypt notes pushed from your other devices. Forgetting it = losing
// the synced corpus.
package com.pulseteam.desktop.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseteam.desktop.data.auth.PasswordCache
import com.pulseteam.desktop.ui.common.StatusDot
import com.pulseteam.desktop.ui.theme.MonoStyle
import com.pulseteam.desktop.ui.theme.PulseColors

@Composable
fun PasswordDialog(
    email: String,
    onUnlock: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf(TextFieldValue("")) }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PulseColors.Bg.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(PulseColors.Bg2, RectangleShape)
                .border(1.dp, PulseColors.Border, RectangleShape)
                .padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(8.dp)
                        .background(PulseColors.Accent, RectangleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text("Unlock sync", color = PulseColors.FgBright, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                StatusDot(PulseColors.Warn, size = 6.dp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Signed in as $email",
                color = PulseColors.FgDim, fontSize = 11.sp, style = MonoStyle,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Enter your password to derive the sync key. " +
                    "Pulse is end-to-end encrypted — the server never sees your password.",
                color = PulseColors.Fg, fontSize = 12.sp, lineHeight = 18.sp,
            )

            Spacer(Modifier.height(20.dp))

            // Password input
            Text(
                "PASSWORD",
                color = PulseColors.FgDim, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PulseColors.BgInput, RectangleShape)
                    .border(1.dp, PulseColors.Border, RectangleShape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        singleLine = true,
                        textStyle = TextStyle(color = PulseColors.Fg, fontSize = 13.sp),
                        cursorBrush = SolidColor(PulseColors.Accent),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (password.text.isEmpty()) {
                                Text("Your password", color = PulseColors.FgDisabled, fontSize = 13.sp)
                            }
                            inner()
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(PulseColors.Bg3, RectangleShape)
                        .border(1.dp, PulseColors.Border, RectangleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clickable { showPassword = !showPassword },
                ) {
                    Text(
                        if (showPassword) "hide" else "show",
                        color = PulseColors.Fg, fontSize = 10.sp, style = MonoStyle,
                    )
                }
            }

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, color = PulseColors.Error, fontSize = 12.sp)
            }

            Spacer(Modifier.height(20.dp))

            // Unlock
            val enabled = password.text.isNotBlank()
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(PulseColors.Accent, RectangleShape)
                        .border(1.dp, PulseColors.Border, RectangleShape)
                        .clickable(enabled = enabled) {
                            PasswordCache.set(password.text.toCharArray())
                            onUnlock()
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Unlock",
                        color = PulseColors.Bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(PulseColors.Bg3, RectangleShape)
                        .border(1.dp, PulseColors.Border, RectangleShape)
                        .clickable { onSkip() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Skip",
                        color = PulseColors.Fg, fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Skip = work offline. You can unlock later via Settings → Account.",
                color = PulseColors.FgDisabled, fontSize = 10.sp, lineHeight = 14.sp,
            )
        }
    }
}
