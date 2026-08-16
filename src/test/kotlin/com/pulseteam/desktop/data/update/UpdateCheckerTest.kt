// SPDX-License-Identifier: Apache-2.0
// Pulse — UpdateChecker unit tests. We exercise the pure `decide()` path
// (parsed manifest → UpdateStatus) without hitting the network. The
// ManifestUnavailableException path (HTTP 404) is covered separately by
// observing the real `check()` against a URL we know returns 404.
package com.pulseteam.desktop.data.update

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class UpdateCheckerTest {

    private val checker = UpdateChecker(currentVersion = "1.0.0")

    @Test
    fun `decide returns Available when manifest has newer version for our os`() {
        val manifest = JSONObject("""
            {
              "version": "1.0.1",
              "windows": { "url": "https://example.com/pulse.exe", "sha256": "abc", "size": 12345 },
              "releaseNotes": "fixes"
            }
        """.trimIndent())
        val status = checker.decide(manifest, currentVersion = "1.0.0", os = "windows")
        assertTrue(status is UpdateStatus.Available, "expected Available, got $status")
        val info = (status as UpdateStatus.Available).info
        assertEquals("1.0.1", info.version)
        assertEquals("https://example.com/pulse.exe", info.url)
        assertEquals(12345L, info.sizeBytes)
    }

    @Test
    fun `decide returns UpToDate when version is equal or older`() {
        val manifest = JSONObject("""
            {
              "version": "0.9.0",
              "windows": { "url": "https://example.com/old.exe", "sha256": "x", "size": 1 }
            }
        """.trimIndent())
        val status = checker.decide(manifest, currentVersion = "1.0.0", os = "windows")
        assertTrue(status is UpdateStatus.UpToDate, "expected UpToDate, got $status")
    }

    @Test
    fun `decide returns UpToDate when our os section is missing`() {
        val manifest = JSONObject("""
            {
              "version": "2.0.0",
              "macos": { "url": "https://example.com/mac.dmg", "sha256": "y", "size": 1 }
            }
        """.trimIndent())
        // We are on Windows, manifest only ships macos. Should be UpToDate (no update for us).
        val status = checker.decide(manifest, currentVersion = "1.0.0", os = "windows")
        assertTrue(status is UpdateStatus.UpToDate, "expected UpToDate when no pkg for our os, got $status")
    }

    @Test
    fun `decide returns Failed when manifest is missing required fields`() {
        val manifest = JSONObject("""
            {
              "version": "1.0.1",
              "windows": { "sha256": "z" }
            }
        """.trimIndent())
        val status = checker.decide(manifest, currentVersion = "1.0.0", os = "windows")
        assertTrue(status is UpdateStatus.Failed, "expected Failed when url blank, got $status")
        assertTrue((status as UpdateStatus.Failed).reason.contains("missing"))
    }

    @Test
    fun `check returns UpToDate when manifest URL returns 404 (no manifest published yet)`() {
        // This is the regression we fixed: previously a 404 logged a WARN and
        // surfaced UpdateStatus.Failed. The expected behaviour on a missing
        // manifest is UpToDate + an INFO log, since the manifest simply
        // hasn't been published yet.
        val checker = UpdateChecker(
            currentVersion = "1.0.0",
            manifestUrl = "https://huggingface.co/this-path-does-not-exist-12345.json",
        )
        val status = runBlocking { checker.check() }
        assertTrue(
            status is UpdateStatus.UpToDate,
            "404 should be treated as up-to-date, got $status",
        )
    }

    @Test
    fun `check returns Failed on malformed JSON`() {
        // Use a data: URL with broken JSON so we don't depend on network
        // for the parse-failure path.
        val checker = UpdateChecker(
            currentVersion = "1.0.0",
            manifestUrl = "data:text/plain,this%20is%20not%20json",
        )
        val status = runBlocking { checker.check() }
        assertTrue(
            status is UpdateStatus.Failed,
            "malformed JSON should be Failed, got $status",
        )
    }
}
