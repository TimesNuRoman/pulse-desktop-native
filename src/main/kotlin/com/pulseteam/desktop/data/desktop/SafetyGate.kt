// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — SafetyGate. The single source of truth for "is this
// host-PC action safe to execute without asking the user?". UI watches
// [state] and renders the confirm dialog when [SafetyState.pending] is
// non-null.
package com.pulseteam.desktop.data.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * How aggressive the SafetyGate is.
 *
 * - [AlwaysConfirm] — every active command shows the dialog. The safe
 *   default. The user has to OK every action.
 * - [OncePerCommand] — within [OncePerCommand.windowMs] (5 min), the
 *   same action summary is auto-approved. Useful when running a series
 *   of "Click on Submit"-style commands.
 * - [Never] — desktop control is enabled but no dialogs are shown.
 *   The user takes full responsibility. Still gated by the [enabled]
 *   master flag in [SafetyState].
 */
enum class SafetyLevel(val windowMs: Long = 0L) {
    AlwaysConfirm,
    OncePerCommand(windowMs = 5 * 60 * 1000L),
    Never,
}

/** UI-visible state of the SafetyGate. */
data class SafetyState(
    /** Master enable. When false, the controller should refuse to even call request(). */
    val enabled: Boolean = true,
    val level: SafetyLevel = SafetyLevel.AlwaysConfirm,
    /** Non-null = there is an action waiting for the user to confirm or cancel. */
    val pending: PendingAction? = null,
    /** Summary text of the most recently auto-approved action. Used by
     *  [SafetyLevel.OncePerCommand] to dedupe within the window. */
    val lastAutoApprovedSummary: String? = null,
    /** When the last auto-approved action was approved. */
    val lastAutoApprovedAt: Long = 0L,
)

/** An action that's been proposed and is waiting for user approval. */
data class PendingAction(
    val action: DesktopAction,
    /** Human-readable summary for the dialog, e.g. `Click at (450, 220) — "Save"?`. */
    val summary: String,
    /** PNG screenshot taken at the moment the action was proposed, for the dialog to preview. */
    val screenshotPath: File?,
)

/**
 * Holds the safety policy + pending action state. UI watches [state]
 * to know when to show the confirm dialog.
 *
 * **Threading:** all methods are safe to call from any thread. The
 * underlying [MutableStateFlow] is thread-safe by contract.
 */
class SafetyGate {
    private val _state = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    /**
     * Update the policy. Called when the user changes the "Safety level"
     * or "Enable desktop control" toggles in Settings.
     */
    fun configure(enabled: Boolean, level: SafetyLevel) {
        _state.value = _state.value.copy(enabled = enabled, level = level)
    }

    /**
     * Decide whether [action] needs user confirmation.
     *
     * - When [SafetyState.enabled] is false, returns `true` (allowed).
     *   The caller is responsible for not invoking request() in that
     *   case; the controller's main entry point is the one that gates
     *   on [SafetyState.enabled].
     * - When [SafetyLevel.AlwaysConfirm], populates [SafetyState.pending]
     *   and returns `false`.
     * - When [SafetyLevel.OncePerCommand], checks if the same [summary]
     *   was approved within the [SafetyLevel.windowMs] window. If yes,
     *   returns `true` (allowed). Otherwise populates pending.
     * - When [SafetyLevel.Never], returns `true` (allowed) and records
     *   the action for the audit log.
     *
     * @return `true` if the action may proceed without further input.
     */
    fun request(action: DesktopAction, summary: String, screenshotPath: File? = null): Boolean {
        val s = _state.value
        if (!s.enabled) return true
        return when (s.level) {
            SafetyLevel.AlwaysConfirm -> {
                _state.value = s.copy(pending = PendingAction(action, summary, screenshotPath))
                false
            }
            SafetyLevel.OncePerCommand -> {
                val now = System.currentTimeMillis()
                val sameRecent = s.lastAutoApprovedSummary == summary &&
                    (now - s.lastAutoApprovedAt) < s.level.windowMs
                if (sameRecent) {
                    _state.value = s.copy(lastAutoApprovedAt = now)
                    true
                } else {
                    _state.value = s.copy(pending = PendingAction(action, summary, screenshotPath))
                    false
                }
            }
            SafetyLevel.Never -> {
                _state.value = s.copy(
                    lastAutoApprovedSummary = summary,
                    lastAutoApprovedAt = System.currentTimeMillis(),
                )
                true
            }
        }
    }

    /** User approved the pending action. Clears pending so the next request() can fire. */
    fun confirm() {
        val s = _state.value
        _state.value = s.copy(
            pending = null,
            lastAutoApprovedSummary = s.pending?.summary ?: s.lastAutoApprovedSummary,
            lastAutoApprovedAt = if (s.pending != null) System.currentTimeMillis() else s.lastAutoApprovedAt,
        )
    }

    /** User rejected the pending action. Same effect as confirm() — just clears pending. */
    fun cancel() {
        _state.value = _state.value.copy(pending = null)
    }
}
