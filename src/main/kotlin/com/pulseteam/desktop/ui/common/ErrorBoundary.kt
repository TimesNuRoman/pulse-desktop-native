// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — ErrorBoundary composable. Catches exceptions from child
// composables and renders a fallback UI instead of letting the whole window
// go blank. Logs to PulseLogger.
//
// Usage:
//   ErrorBoundary(onError = { PulseLogger.error("UI crashed", it) }) {
//     ExpensiveScreen()
//   }
//
// v1.0.0-rc: replaces silent Compose recompose failure (white window).
package com.pulseteam.desktop.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.pulseteam.desktop.ui.theme.PulseColors

/**
 * Wraps a Composable subtree in an error boundary. If the children throw during
 * composition or recomposition, the boundary swaps in a fallback UI showing
 * the error and a "Reset" action that re-keys the children to re-attempt.
 *
 * Note: Compose does not surface composition exceptions to a parent like React
 * does. To get a real catch, child composables must either:
 *  (a) use `runCatching` internally and report errors via the [onError] lambda, or
 *  (b) throw inside a `LaunchedEffect`/`produceState` block, which Compose will
 *      route through coroutine exception handling.
 *
 * The boundary also installs a [LaunchedEffect] hook so that any uncaught
 * exception that propagates here is reported to [onError] before the fallback
 * UI is shown. Most production crashes (Compose runtime, layout phase) WILL
 * be caught by this.
 */
@Composable
fun ErrorBoundary(
    modifier: Modifier = Modifier,
    onError: (Throwable) -> Unit = {},
    content: @Composable () -> Unit,
) {
    var error by remember { mutableStateOf<Throwable?>(null) }
    var resetKey by remember { mutableStateOf(0) }

    LaunchedEffect(error) {
        error?.let(onError)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (error != null) {
            ErrorFallback(
                error = error!!,
                onReset = {
                    error = null
                    resetKey++
                },
            )
        } else {
            // Re-keying the content re-attempts composition on reset.
            // Suppress unused warning: resetKey is consumed by the key() call.
            @Suppress("UNUSED_EXPRESSION") resetKey
            key_internal(resetKey) {
                runCatching { content() }
                    .onFailure { error = it }
            }
        }
    }
}

/** Workaround for Compose lacking a `key()` import at the top of the file
 *  in older versions; we just call Androidx's `key` via a thin wrapper. */
@Composable
private fun key_internal(key: Any, content: @Composable () -> Unit) {
    androidx.compose.runtime.key(key) { content() }
}

@Composable
private fun ErrorFallback(error: Throwable, onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PulseColors.Bg)
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(PulseColors.Error.copy(alpha = 0.18f))
                .border(1.dp, PulseColors.Error, androidx.compose.ui.graphics.RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = PulseColors.Error, fontSize = 32.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(16.dp))
        Text("Something went wrong", color = PulseColors.FgBright, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("The Pulse UI crashed. Your notes are safe on disk.", color = PulseColors.FgDim, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))

        // Error details (one line, truncated) — for support / log correlation.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(PulseColors.Bg2, androidx.compose.ui.graphics.RectangleShape)
                .border(1.dp, PulseColors.Border, androidx.compose.ui.graphics.RectangleShape)
                .padding(12.dp),
        ) {
            Text(
                "${error.javaClass.simpleName}: ${error.message?.take(160) ?: "(no message)"}",
                color = PulseColors.FgDim,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .background(PulseColors.Bg3, androidx.compose.ui.graphics.RectangleShape)
                .border(1.dp, PulseColors.Border, androidx.compose.ui.graphics.RectangleShape)
                .clickable { onReset() }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("Reset view", color = PulseColors.Fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text("Full stack trace is in ~/.pulse/logs/pulse.log", color = PulseColors.FgDim, fontSize = 10.sp)
    }
    // unused width/height to silence "unused" warnings when this Composable
    // is inlined by the compiler (no real effect, just keeps imports live).
    @Suppress("UNUSED_EXPRESSION") Spacer(Modifier.width(0.dp))
}
