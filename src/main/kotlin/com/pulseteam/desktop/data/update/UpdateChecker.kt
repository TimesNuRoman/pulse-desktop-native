// SPDX-License-Identifier: Apache-2.0
// Pulse — UpdateChecker. Polls a JSON manifest on app start, compares
// the latest version to ours, and exposes an `UpdateAvailable` state.
//
// v0.7.0-rc: We do NOT do in-place patching (too risky for unsigned
// installers, and jpackage's "automatic" updater is JVM-21+ only).
// Instead, when a newer manifest version is detected, we show a banner
// with a "Download" button that opens the URL in the system browser.
// The user downloads the new .exe/.msi/.dmg and runs it; the installer
// replaces the existing app. User data (~/.pulse/) is never touched
// by the installer.
//
// Manifest schema (https://ownlocalml.com/updates/{channel}.json):
//   {
//     "version": "1.0.1",
//     "releaseDate": "2026-09-01T12:00:00Z",
//     "windows": {
//       "url": "https://ownlocalml.com/downloads/Pulse-1.0.1.exe",
//       "sha256": "abc...",
//       "size": 73400320
//     },
//     "macos":   { ... },
//     "linux":   { ... },
//     "releaseNotes": "..."
//   }
//
// We use the OS-detected channel (windows / macos / linux) and ignore
// the other two. If a field is missing for our OS, the manifest is
// treated as "no update available for this platform".
package com.pulseteam.desktop.data.update

import com.pulseteam.desktop.data.log.PulseLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class UpdateInfo(
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
)

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpToDate(val current: String) : UpdateStatus()
    data class Available(val info: UpdateInfo) : UpdateStatus()
    data class Failed(val reason: String) : UpdateStatus()
}

class UpdateChecker(
    /** Current Pulse version. Hardcoded in build.gradle.kts. */
    val currentVersion: String = "1.0.0",
    /** Where the manifest lives. Configurable so the dev build can point at a local file. */
    private val manifestUrl: String = "https://ownlocalml.com/updates/windows-kotlin.json",
) {

    suspend fun check(): UpdateStatus = withContext(Dispatchers.IO) {
        try {
            val text = fetch(manifestUrl)
            val manifest = parse(text) ?: return@withContext UpdateStatus.Failed("manifest: empty or invalid JSON")
            decide(manifest, currentVersion, detectOs())
        } catch (t: ManifestUnavailableException) {
            // 404 / DNS failure / TLS error: the manifest isn't published yet
            // (or the network is offline). This is expected on first release
            // — treat as up-to-date and log at INFO, not WARN. Real failure
            // modes (HTTP 500, parse error) still surface as UpdateStatus.Failed.
            PulseLogger.info("Update check skipped (manifest unavailable)",
                mapOf("url" to manifestUrl, "err" to t.message))
            UpdateStatus.UpToDate(currentVersion)
        } catch (t: Throwable) {
            PulseLogger.warn("Update check failed", mapOf("err" to t.message))
            UpdateStatus.Failed(t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Pure function: turn a parsed manifest into an [UpdateStatus]. Extracted
     * from [check] so it can be unit-tested without an HTTP layer. Visible
     * to tests via `internal` (same module).
     */
    internal fun decide(manifest: org.json.JSONObject, currentVersion: String, os: String): UpdateStatus {
        val pkg = manifest.optJSONObject(os) ?: return UpdateStatus.UpToDate(currentVersion)
        val latest = manifest.optString("version", "")
        val url = pkg.optString("url", "")
        val sha256 = pkg.optString("sha256", "")
        val size = pkg.optLong("size", 0L)
        val notes = manifest.optString("releaseNotes", "")
        if (latest.isBlank() || url.isBlank()) {
            return UpdateStatus.Failed("manifest: missing version or url for $os")
        }
        if (compareVersions(latest, currentVersion) <= 0) {
            return UpdateStatus.UpToDate(currentVersion)
        }
        return UpdateStatus.Available(
            UpdateInfo(
                version = latest,
                url = url,
                sha256 = sha256,
                sizeBytes = size,
                releaseNotes = notes,
            )
        )
    }

    /** Thrown by [fetch] when the manifest is not reachable. Distinct from
     *  HTTP 5xx (server problem) — those bubble out of [check] as Failed. */
    private class ManifestUnavailableException(message: String) : RuntimeException(message)

    /**
     * Open the URL in the system browser. We do this from a coroutine
     * because Desktop.browse is synchronous and we want to keep the
     * UI thread free.
     */
    fun openDownload(url: String) {
        try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("mac") -> arrayOf("open", url)
                os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
                else -> arrayOf("xdg-open", url)
            }
            ProcessBuilder(*cmd).start()
            PulseLogger.info("Opened update download in browser", mapOf("url" to url))
        } catch (t: Throwable) {
            PulseLogger.warn("Failed to open download URL", mapOf("url" to url, "err" to t.message))
        }
    }

    private fun detectOs(): String {
        val n = System.getProperty("os.name").lowercase()
        return when {
            n.contains("win") -> "windows"
            n.contains("mac") -> "macos"
            else -> "linux"
        }
    }

    /**
     * Standard semver comparison. Returns 1 if a > b, -1 if a < b, 0 if equal.
     * Missing parts are treated as 0 (so 1.0 == 1.0.0).
     */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun fetch(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Pulse/1.0 (desktop; +https://ownlocalml.com)")
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            // 404 (no manifest yet), 5xx (server problem), network errors all
            // surface here. 404 is "expected" until we publish; 5xx is a real
            // issue. We split at the caller: 404 → ManifestUnavailableException
            // (treated as up-to-date), everything else → Failed.
            if (code == 404) {
                throw ManifestUnavailableException("HTTP 404 (no manifest published)")
            }
            throw RuntimeException("manifest HTTP $code")
        }
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()
        return text
    }

    private fun parse(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()
}
