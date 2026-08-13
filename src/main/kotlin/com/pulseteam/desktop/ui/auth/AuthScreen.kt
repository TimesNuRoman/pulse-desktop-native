// SPDX-License-Identifier: Apache-2.0
// Pulse — Auth screen. Single window with three modes: login, register, forgot/reset.
// All HTTP errors come back as AuthApiException(status, message); we surface
// the message as a red status line under the form.
//
// v0.3.0: Wired to real pulse-cf-worker at api.ownlocalml.com.
package com.pulseteam.desktop.ui.auth

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseteam.desktop.data.auth.AuthApi
import com.pulseteam.desktop.data.auth.AuthApiException
import com.pulseteam.desktop.data.auth.AuthSession
import com.pulseteam.desktop.ui.common.StatusDot
import com.pulseteam.desktop.ui.theme.MonoStyle
import com.pulseteam.desktop.ui.theme.PulseColors
import kotlinx.coroutines.launch

private enum class Mode { Login, Register, Forgot }

@Composable
fun AuthScreen(
    session: AuthSession,
    api: AuthApi = AuthApi(),
    onAuthenticated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(Mode.Login) }
    var email by remember { mutableStateOf(TextFieldValue("")) }
    var password by remember { mutableStateOf(TextFieldValue("")) }
    var showPassword by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PulseColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .background(PulseColors.Bg2, RectangleShape)
                .border(1.dp, PulseColors.Border, RectangleShape)
                .padding(28.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .background(PulseColors.Accent, RectangleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("P", color = PulseColors.Bg, fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(10.dp))
                Text("Pulse", color = PulseColors.FgBright, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                StatusDot(PulseColors.Green, size = 6.dp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Local-first notes + chat + AI",
                color = PulseColors.FgDim, fontSize = 12.sp, style = MonoStyle,
            )

            Spacer(Modifier.height(24.dp))

            // Mode tabs
            Row(modifier = Modifier.fillMaxWidth()) {
                AuthTab("Log in", mode == Mode.Login) { mode = Mode.Login; error = null }
                Spacer(Modifier.width(6.dp))
                AuthTab("Sign up", mode == Mode.Register) { mode = Mode.Register; error = null }
                Spacer(Modifier.width(6.dp))
                AuthTab("Reset", mode == Mode.Forgot) { mode = Mode.Forgot; error = null }
            }

            Spacer(Modifier.height(20.dp))

            // Email field
            FieldLabel("Email")
            FieldInput(
                value = email,
                onChange = { email = it; error = null },
                placeholder = "you@domain.com",
            )

            if (mode != Mode.Forgot) {
                Spacer(Modifier.height(12.dp))
                FieldLabel("Password")
                FieldInput(
                    value = password,
                    onChange = { password = it; error = null },
                    placeholder = if (mode == Mode.Register) "Choose a strong password" else "Your password",
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
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
                    },
                )
                if (mode == Mode.Register) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "12+ chars. This is your encryption key — pick something you remember.",
                        color = PulseColors.FgDim, fontSize = 10.sp, lineHeight = 14.sp,
                    )
                }
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    error!!,
                    color = PulseColors.Error, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            if (status != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    status!!,
                    color = PulseColors.Green, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }

            Spacer(Modifier.height(20.dp))

            // Submit
            val label = when (mode) {
                Mode.Login -> if (inFlight) "Signing in..." else "Log in"
                Mode.Register -> if (inFlight) "Creating account..." else "Create account"
                Mode.Forgot -> if (inFlight) "Sending link..." else "Send reset link"
            }
            val enabled = !inFlight && email.text.isNotBlank() &&
                (mode == Mode.Forgot || password.text.length >= 8)
            SubmitButton(label = label, enabled = enabled) {
                inFlight = true
                error = null
                status = null
                scope.launch {
                    try {
                        when (mode) {
                            Mode.Login -> {
                                val r = api.login(email.text.trim(), password.text)
                                session.login(com.pulseteam.desktop.data.auth.Session(r.token, r.user))
                                status = "Signed in as ${r.user.email}"
                                onAuthenticated()
                            }
                            Mode.Register -> {
                                val r = api.register(email.text.trim(), password.text)
                                session.login(com.pulseteam.desktop.data.auth.Session(r.token, r.user))
                                status = "Welcome, ${r.user.email}"
                                onAuthenticated()
                            }
                            Mode.Forgot -> {
                                api.forgot(email.text.trim())
                                status = "If the address is registered, a reset link is on its way."
                            }
                        }
                    } catch (e: AuthApiException) {
                        error = when (e.statusCode) {
                            401 -> "Wrong email or password."
                            409 -> "That email is already registered — try Log in."
                            400 -> e.message ?: "Bad request."
                            else -> "Server error ${e.statusCode}. ${e.message}"
                        }
                    } catch (e: Throwable) {
                        error = "Network: ${e.message ?: e.javaClass.simpleName}"
                    } finally {
                        inFlight = false
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Pulse is end-to-end encrypted. Your password derives the sync key via scrypt — we never see it.",
                color = PulseColors.FgDisabled, fontSize = 10.sp, lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun AuthTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) PulseColors.Accent else PulseColors.Bg3
    val fg = if (selected) PulseColors.Bg else PulseColors.Fg
    Box(
        modifier = Modifier
            .background(bg, RectangleShape)
            .border(1.dp, PulseColors.Border, RectangleShape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text.uppercase(),
        color = PulseColors.FgDim, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun FieldInput(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    placeholder: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
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
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = PulseColors.Fg, fontSize = 13.sp),
                cursorBrush = SolidColor(PulseColors.Accent),
                visualTransformation = visualTransformation,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.text.isEmpty()) {
                        Text(placeholder, color = PulseColors.FgDisabled, fontSize = 13.sp)
                    }
                    inner()
                },
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun SubmitButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (enabled) PulseColors.Accent else PulseColors.Bg3,
                RectangleShape,
            )
            .border(1.dp, PulseColors.Border, RectangleShape)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) PulseColors.Bg else PulseColors.FgDim,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

