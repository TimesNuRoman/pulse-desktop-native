// SPDX-License-Identifier: Apache-2.0
// Pulse — SkillRepository persistence tests. Verifies the file-write path
// stays correct after the Windows AccessDeniedException fix (commit 5e...):
//   1. upsert persists the skill to ~/.pulse/skills.json
//   2. rapid back-to-back upserts don't leave stale *.tmp files behind
//   3. delete removes the skill from disk
//
// These tests read/write the real ~/.pulse/skills.json (same as production)
// to keep the test honest. They use a unique skill id prefix so we don't
// clobber other test runs or a real install.
package com.pulseteam.desktop.data.skills

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.streams.toList

class SkillRepositoryPersistenceTest {

    private val testIdPrefix = "test-${System.currentTimeMillis()}-"
    private val file: Path = Paths.get(System.getProperty("user.home"), ".pulse", "skills.json")
    private val repo = SkillRepository()

    @BeforeEach
    fun waitForLoad() {
        // SkillRepository launches an async load() in init; give it a moment.
        runBlocking {
            withTimeout(2_000) {
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 1_000) {
                    if (repo.skills.value.isNotEmpty() || System.currentTimeMillis() - start > 50) {
                        delay(20)
                        break
                    }
                    delay(20)
                }
            }
        }
    }

    @AfterEach
    fun cleanup() {
        // Remove only skills we created in this run (id starts with the prefix).
        val ours = repo.skills.value.filter { it.id.startsWith(testIdPrefix) }
        ours.forEach { repo.delete(it.id) }
        // Wait briefly for the deletes to flush.
        runBlocking { delay(100) }
        // Clean up any leftover tmp files.
        if (file.parent.exists()) {
            Files.newDirectoryStream(file.parent, "skills.json.tmp.*").use { stream ->
                stream.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    @Test
    fun `upsert persists skill to disk`() {
        val s = Skill(
            id = "${testIdPrefix}persist",
            name = "Persist test",
            body = "body",
            triggers = listOf("persist"),
        )
        repo.upsert(s)
        runBlocking {
            withTimeout(3_000) {
                // Poll the file until the new id appears.
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 2_000) {
                    if (file.exists() && Files.readString(file).contains(testIdPrefix)) return@withTimeout
                    delay(20)
                }
            }
        }
        assertTrue(file.exists(), "skills.json should exist after upsert")
        val text = Files.readString(file)
        assertTrue(text.contains(testIdPrefix), "file should contain our test id")
        assertTrue(text.contains("Persist test"), "file should contain skill name")
    }

    @Test
    fun `rapid back-to-back upserts do not leave stale tmp files`() {
        val ids = (0..5).map { i -> "${testIdPrefix}rapid-$i" }
        ids.forEach { i ->
            repo.upsert(Skill(id = i, name = "Rapid $i", body = "b", triggers = emptyList()))
        }
        // Wait for all writes to settle.
        runBlocking { delay(300) }
        // No skills.json.tmp.* file should linger (we clean them up on
        // successful writes; if a write is still in progress the file uses
        // a unique nanoTime suffix so they don't collide).
        val tmpFiles = if (file.parent.exists()) {
            file.parent.listDirectoryEntries("skills.json.tmp.*").toList()
        } else emptyList()
        assertTrue(
            tmpFiles.isEmpty(),
            "expected no stale tmp files after rapid writes, found: $tmpFiles",
        )
    }

    @Test
    fun `delete removes skill from disk`() {
        val s = Skill(
            id = "${testIdPrefix}delete-me",
            name = "Delete me",
            body = "b",
            triggers = emptyList(),
        )
        repo.upsert(s)
        runBlocking { delay(150) }
        assertTrue(Files.readString(file).contains("Delete me"), "skill should be in file before delete")
        repo.delete(s.id)
        runBlocking { delay(150) }
        val text = Files.readString(file)
        assertTrue(!text.contains("Delete me"), "skill should be removed from file after delete")
    }
}
