// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — minimal file-based logger. Writes to ~/.pulse/logs/pulse.log
// with size-based rotation (5 MB cap, single .1 backup). No external deps.
//
// Usage:
//   PulseLogger.info("note created", mapOf("id" to id))
//   PulseLogger.warn("sync conflict", mapOf("note" to title))
//   PulseLogger.error("http failed", throwable, mapOf("url" to url))
//
// v1.0.0-rc: replaces 0-line ad-hoc println in main app.
package com.pulseteam.desktop.data.log

import java.io.File
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PulseLogger {
    private const val MAX_BYTES: Long = 5L * 1024 * 1024  // 5 MB
    private const val BACKUP_NAME = "pulse.log.1"

    private val logDir: Path = Paths.get(System.getProperty("user.home"), ".pulse", "logs")
    private val logFile: File = logDir.resolve("pulse.log").toFile()
    private val backupFile: File = logDir.resolve(BACKUP_NAME).toFile()

    private val tsFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            .withZone(ZoneId.systemDefault())

    @Volatile private var writer: FileWriter? = null
    private val lock = Any()

    init {
        runCatching {
            Files.createDirectories(logDir)
            openWriter()
        }
    }

    private fun openWriter() {
        writer = FileWriter(logFile, /* append = */ true)
    }

    private fun rotateIfNeeded() {
        if (logFile.length() < MAX_BYTES) return
        // Close current writer, rotate, reopen.
        runCatching { writer?.close() }
        // Overwrite .1 (single backup, last 5MB).
        runCatching {
            if (backupFile.exists()) backupFile.delete()
            logFile.renameTo(backupFile)
        }
        openWriter()
    }

    fun info(message: String, context: Map<String, Any?> = emptyMap()) =
        write("INFO", message, null, context)

    fun warn(message: String, context: Map<String, Any?> = emptyMap()) =
        write("WARN", message, null, context)

    fun error(message: String, throwable: Throwable? = null, context: Map<String, Any?> = emptyMap()) =
        write("ERROR", message, throwable, context)

    private fun write(level: String, message: String, throwable: Throwable?, context: Map<String, Any?>) {
        synchronized(lock) {
            runCatching {
                rotateIfNeeded()
                val line = format(level, message, throwable, context)
                val w = writer ?: return@runCatching
                w.append(line)
                w.append('\n')
                w.flush()
            }
        }
    }

    private fun format(level: String, message: String, throwable: Throwable?, context: Map<String, Any?>): String {
        val sb = StringBuilder(128)
        sb.append(tsFormatter.format(Instant.now()))
        sb.append(' ').append(level.padEnd(5))
        sb.append(" | ").append(message.replace('\n', ' '))
        if (context.isNotEmpty()) {
            sb.append(" | ")
            context.entries.joinTo(sb, ", ") { (k, v) -> "$k=$v" }
        }
        if (throwable != null) {
            sb.append('\n')
            sb.append("    ").append(throwable.javaClass.name)
            sb.append(": ").append(throwable.message?.replace('\n', ' '))
            // First 5 stack frames — enough for "where" without filling the log.
            throwable.stackTrace.take(5).forEach { f ->
                sb.append('\n').append("        at ").append(f.toString())
            }
        }
        return sb.toString()
    }

    /** Path to the active log file (for "Share debug log" feature later). */
    fun logPath(): Path = logFile.toPath()

    /** Returns the last N lines of the log (for in-app debug viewer). */
    fun tail(maxLines: Int = 200): List<String> {
        if (!logFile.exists()) return emptyList()
        return runCatching {
            logFile.readLines().takeLast(maxLines)
        }.getOrDefault(emptyList())
    }

    /** Install JVM uncaught exception handler. Call once at app start. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error(
                "Uncaught exception in thread ${thread.name}",
                throwable,
                mapOf("thread" to thread.name),
            )
            previous?.uncaughtException(thread, throwable)
        }
    }
}
