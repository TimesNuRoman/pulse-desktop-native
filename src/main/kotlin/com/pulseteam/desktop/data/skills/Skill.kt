// SPDX-License-Identifier: Apache-2.0
// Pulse — Skill model.
//
// A Skill is a saved prompt the user can drop into any chat. Each skill
// has a body (the actual instruction set), a list of triggers (keywords
// that auto-activate the skill when present in the user message), an
// optional category, and a pinned flag for skills the user wants to
// always see in the popover.
//
// v0.7.0-rc: Skills are stored as JSON in ~/.pulse/skills.json. We
// hand-roll the JSON encoder/decoder (no kotlinx-serialization dep)
// because the shape is small and stable.
//
// Accept-rate: we track "uses" and "accepted" counts. The displayed
// percentage is `accepted / max(uses, 1)`. The tooltip says the period
// is "all time, since skill creation" — see SkillsScreen for the
// exact copy.
package com.pulseteam.desktop.data.skills

import java.util.UUID

data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    /** The actual instruction text prepended to the LLM prompt. */
    val body: String,
    /**
     * Trigger keywords. Any of these present in the user message will
     * auto-activate the skill. Empty list = manual activation only.
     * Syntax (see SkillsScreen help overlay):
     *   foo            keyword (case-insensitive substring)
     *   "exact phrase"  literal multi-word match
     *   /regex/         regular expression
     *   !tag            required tag prefix (alias for tag:tag)
     */
    val triggers: List<String> = emptyList(),
    val category: String = "General",
    val pinned: Boolean = false,
    val uses: Int = 0,
    val accepted: Int = 0,
    /** Last N activations for the History tab. Capped at 50. */
    val history: List<SkillActivation> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

data class SkillActivation(
    val timestamp: Long,
    val userMessage: String,
    val autoTriggered: Boolean,
    val accepted: Boolean? = null, // null = user didn't vote
)

/**
 * Compute the accept-rate as a 0..1 fraction. Returns null if no
 * uses yet, so the UI can render a dash instead of "0%".
 */
fun Skill.acceptRate(): Double? {
    if (uses <= 0) return null
    return accepted.toDouble() / uses.toDouble()
}
