// SPDX-License-Identifier: Apache-2.0
// Pulse — SkillRepositoryTest. Verifies trigger matching across the 4
// trigger syntaxes (keyword, "exact phrase", /regex/, !tag) and the
// accept-rate math.
package com.pulseteam.desktop.data.skills

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkillRepositoryTest {

    private val review = Skill(
        name = "Code review",
        body = "review body",
        triggers = listOf("review", "code", "/class \\w+/"),
        pinned = true,
    )
    private val summarize = Skill(
        name = "Summarize",
        body = "summarize body",
        triggers = listOf("summarize", "tl;dr", "\"exact phrase\""),
    )
    private val tagging = Skill(
        name = "Code tag",
        body = "tag body",
        triggers = listOf("!code"),
    )
    private val repo: SkillRepository

    init {
        // SkillRepository's init launches an async load() that seeds 2
        // example skills on first run (or reads ~/.pulse/skills.json).
        // We wait for that load to complete before wiping + reseeding, so
        // the StateFlow is in a known state when each test starts.
        repo = SkillRepository()
        runBlocking {
            withTimeout(2_000) {
                // Poll until the load finishes (skills is non-empty or 50ms
                // have passed since the repo was created).
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 1_000) {
                    if (repo.skills.value.isNotEmpty() || System.currentTimeMillis() - start > 50) {
                        // Wait one more frame to let any pending write complete.
                        kotlinx.coroutines.delay(20)
                        break
                    }
                    kotlinx.coroutines.delay(20)
                }
            }
        }
        // Wipe whatever loaded (seeded example skills or any leftovers from
        // a prior test run that wrote to ~/.pulse/skills.json).
        repo.skills.value.forEach { repo.delete(it.id) }
        repo.upsert(review)
        repo.upsert(summarize)
        repo.upsert(tagging)
    }

    @Test
    fun `keyword trigger matches case-insensitive substring`() {
        val matched = repo.matching("Please REVIEW this")
        assertTrue(matched.any { it.id == review.id }, "review trigger should match 'REVIEW'")
    }

    @Test
    fun `exact phrase trigger only matches the literal phrase`() {
        assertTrue(repo.matching("Use the exact phrase now").any { it.id == summarize.id })
        assertTrue(repo.matching("EXACT PHRASE elsewhere").any { it.id == summarize.id })
        assertTrue(repo.matching("exactphrasenospace").none { it.id == summarize.id },
            "Without space the literal should not match")
    }

    @Test
    fun `regex trigger matches via Regex containsMatchIn`() {
        val matched = repo.matching("Look at MyClass implementation")
        assertTrue(matched.any { it.id == review.id }, "/class \\w+/ should match 'MyClass'")
    }

    @Test
    fun `tag trigger matches tag-prefixed content`() {
        val matched = repo.matching("Working on [code] and the design")
        assertTrue(matched.any { it.id == tagging.id })
        assertTrue(repo.matching("Working on code only").none { it.id == tagging.id },
            "Bare 'code' should not match the !code tag")
    }

    @Test
    fun `pinned skills come first`() {
        val matched = repo.matching("Tell me about code and review")
        assertEquals(review.id, matched.first().id, "pinned 'review' should sort first")
    }

    @Test
    fun `acceptRate returns null when no uses`() {
        assertNull(review.acceptRate())
    }

    @Test
    fun `acceptRate computes accepted over uses`() {
        val s = review.copy(uses = 10, accepted = 7)
        assertEquals(0.7, s.acceptRate()!!, 0.001)
    }

    @Test
    fun `no triggers means no auto-match`() {
        val manual = Skill(name = "Manual", body = "b", triggers = emptyList())
        repo.upsert(manual)
        val matched = repo.matching("anything goes here")
        assertTrue(matched.none { it.id == manual.id })
        repo.delete(manual.id)
    }
}
