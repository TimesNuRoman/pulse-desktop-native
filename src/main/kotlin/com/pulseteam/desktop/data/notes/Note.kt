// SPDX-License-Identifier: Apache-2.0
// Pulse — Note data class + NoteRepository.
// CRUD + FTS4 search. Repository is the only place that touches Database.
package com.pulseteam.desktop.data.notes

import com.pulseteam.desktop.data.db.Database
import java.sql.ResultSet
import java.util.UUID

data class Note(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val preview: String
        get() = body.lineSequence().firstOrNull()?.trim().orEmpty().take(120)
}

object NoteRepository {
    private fun now() = System.currentTimeMillis()

    fun list(): List<Note> {
        val out = mutableListOf<Note>()
        Database.open().prepareStatement(
            "SELECT id, title, body, created_at, updated_at FROM notes ORDER BY updated_at DESC",
        ).use { ps ->
            ps.executeQuery().use { rs -> while (rs.next()) out += rs.toNote() }
        }
        return out
    }

    fun get(id: String): Note? {
        Database.open().prepareStatement(
            "SELECT id, title, body, created_at, updated_at FROM notes WHERE id = ?",
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.toNote() else null }
        }
    }

    fun create(title: String, body: String = ""): Note {
        val now = now()
        val note = Note(UUID.randomUUID().toString(), title.ifBlank { "Untitled" }, body, now, now)
        Database.open().prepareStatement(
            "INSERT INTO notes(id, title, body, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, note.id); ps.setString(2, note.title); ps.setString(3, note.body)
            ps.setLong(4, note.createdAt); ps.setLong(5, note.updatedAt)
            ps.executeUpdate()
        }
        return note
    }

    /** Create a note with a server-supplied id and updatedAt. Used by sync pull. */
    fun createRaw(id: String, title: String, body: String, updatedAt: Long): Note {
        val now = now()
        val note = Note(id, title.ifBlank { "Untitled" }, body, now, updatedAt)
        Database.open().prepareStatement(
            "INSERT OR REPLACE INTO notes(id, title, body, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, note.title); ps.setString(3, note.body)
            ps.setLong(4, now); ps.setLong(5, updatedAt)
            ps.executeUpdate()
        }
        return note
    }

    /** Update with explicit updatedAt (sync pull path). */
    fun updateRaw(id: String, title: String, body: String, updatedAt: Long) {
        Database.open().prepareStatement(
            "UPDATE notes SET title = ?, body = ?, updated_at = ? WHERE id = ?",
        ).use { ps ->
            ps.setString(1, title.ifBlank { "Untitled" })
            ps.setString(2, body)
            ps.setLong(3, updatedAt)
            ps.setString(4, id)
            ps.executeUpdate()
        }
    }

    fun update(id: String, title: String, body: String) {
        Database.open().prepareStatement(
            "UPDATE notes SET title = ?, body = ?, updated_at = ? WHERE id = ?",
        ).use { ps ->
            ps.setString(1, title.ifBlank { "Untitled" })
            ps.setString(2, body)
            ps.setLong(3, now())
            ps.setString(4, id)
            ps.executeUpdate()
        }
    }

    fun delete(id: String) {
        Database.open().prepareStatement("DELETE FROM notes WHERE id = ?").use { ps ->
            ps.setString(1, id); ps.executeUpdate()
        }
    }

    /**
     * FTS4 search across title + body. Uses MATCH with a quoted query for phrase
     * search; falls back to LIKE on the raw table for very short queries.
     * Results ordered by rank (best first).
     */
    fun search(query: String, limit: Int = 20): List<Note> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<Note>()
        val conn = Database.open()
        // Try FTS MATCH first
        val matchQ = q.replace("\"", "\"\"").split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { "\"$it\"*" }
        if (matchQ.isNotBlank()) {
            try {
                conn.prepareStatement(
                    """
                    SELECT n.id, n.title, n.body, n.created_at, n.updated_at
                    FROM notes n
                    JOIN notes_fts f ON f.rowid = n.rowid
                    WHERE notes_fts MATCH ?
                    ORDER BY f.rank ASC
                    LIMIT ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, matchQ); ps.setInt(2, limit)
                    ps.executeQuery().use { rs -> while (rs.next()) out += rs.toNote() }
                }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {
                // FTS MATCH can throw on malformed queries; fall through to LIKE
            }
        }
        // Fallback: LIKE on title + body
        conn.prepareStatement(
            """
            SELECT id, title, body, created_at, updated_at
            FROM notes
            WHERE title LIKE ? OR body LIKE ?
            ORDER BY updated_at DESC
            LIMIT ?
            """.trimIndent(),
        ).use { ps ->
            val like = "%$q%"; ps.setString(1, like); ps.setString(2, like); ps.setInt(3, limit)
            ps.executeQuery().use { rs -> while (rs.next()) out += rs.toNote() }
        }
        return out
    }

    /**
     * Find every note that links to `targetTitle` via `[[Title]]` or `[[Title|body]]`.
     * Scans bodies with LIKE for the link token. For 10-1000 notes this is fast
     * (no FTS for [[link]] syntax). For tens of thousands, switch to a
     * dedicated `note_links` table maintained by triggers.
     */
    fun backlinksFor(targetTitle: String, limit: Int = 20): List<Note> {
        val title = targetTitle.trim()
        if (title.isEmpty()) return emptyList()
        val out = mutableListOf<Note>()
        // Match either [[Title]] or [[Title|...]]
        Database.open().prepareStatement(
            """
            SELECT id, title, body, created_at, updated_at
            FROM notes
            WHERE body LIKE ?
            ORDER BY updated_at DESC
            LIMIT ?
            """.trimIndent(),
        ).use { ps ->
            // Escape '%' and '_' in title (SQL LIKE metachars)
            val safe = title.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            ps.setString(1, "%[[${safe}%")
            ps.setInt(2, limit)
            ps.executeQuery().use { rs -> while (rs.next()) out += rs.toNote() }
        }
        return out
    }

    private fun ResultSet.toNote(): Note = Note(
        id = getString("id"),
        title = getString("title"),
        body = getString("body"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
    )
}
