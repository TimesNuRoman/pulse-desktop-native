// SPDX-License-Identifier: Apache-2.0
// Pulse — local SQLite database. Stores notes + FTS4 virtual table for search.
// Path: <user.home>/.pulse/notes.db (matches pulse-android convention).
package com.pulseteam.desktop.data.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

object Database {
    @Volatile private var conn: Connection? = null

    fun open(): Connection {
        conn?.let { return it }
        synchronized(this) {
            conn?.let { return it }
            val dbDir = Path.of(System.getProperty("user.home"), ".pulse")
            Files.createDirectories(dbDir)
            val dbFile = dbDir.resolve("notes.db")
            val url = "jdbc:sqlite:${dbFile.toAbsolutePath()}"
            val c = DriverManager.getConnection(url)
            c.autoCommit = true
            c.createStatement().use { st ->
                // v0.2.1 migration: drop the broken v0.2.0 FTS table (had content_rowid
                // param which SQLite FTS4 doesn't accept). Triggers go too.
                runCatching { st.executeUpdate("DROP TABLE IF EXISTS notes_fts") }
                runCatching { st.executeUpdate("DROP TRIGGER IF EXISTS notes_ai") }
                runCatching { st.executeUpdate("DROP TRIGGER IF EXISTS notes_ad") }
                runCatching { st.executeUpdate("DROP TRIGGER IF EXISTS notes_au") }
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS notes (
                        id         TEXT PRIMARY KEY,
                        title      TEXT NOT NULL,
                        body       TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // FTS4 virtual table mirrors notes.title + notes.body for fast LIKE/MATCH search.
                // content='notes' + rowid linking via triggers below.
                st.executeUpdate(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts4(
                        content='notes',
                        title,
                        body
                    )
                    """.trimIndent(),
                )
                // Triggers keep FTS index in sync with notes table.
                st.executeUpdate(
                    """
                    CREATE TRIGGER IF NOT EXISTS notes_ai AFTER INSERT ON notes BEGIN
                        INSERT INTO notes_fts(rowid, title, body) VALUES (new.rowid, new.title, new.body);
                    END
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TRIGGER IF NOT EXISTS notes_ad AFTER DELETE ON notes BEGIN
                        INSERT INTO notes_fts(notes_fts, rowid, title, body) VALUES('delete', old.rowid, old.title, old.body);
                    END
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TRIGGER IF NOT EXISTS notes_au AFTER UPDATE ON notes BEGIN
                        INSERT INTO notes_fts(notes_fts, rowid, title, body) VALUES('delete', old.rowid, old.title, old.body);
                        INSERT INTO notes_fts(rowid, title, body) VALUES (new.rowid, new.title, new.body);
                    END
                    """.trimIndent(),
                )
            }
            conn = c
            return c
        }
    }
}
