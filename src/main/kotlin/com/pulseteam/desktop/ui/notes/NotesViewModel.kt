// SPDX-License-Identifier: Apache-2.0
// Pulse — NotesViewModel. Owns the note list + open note + search state.
// v0.3.0: accepts SyncEngine + AuthSession; updateNote + createFromChat
// fire-and-forget push the changed note through end-to-end encryption.
package com.pulseteam.desktop.ui.notes

import com.pulseteam.desktop.data.auth.AuthSession
import com.pulseteam.desktop.data.auth.PasswordCache
import com.pulseteam.desktop.data.notes.Note
import com.pulseteam.desktop.data.notes.NoteRepository
import com.pulseteam.desktop.data.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotesViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val syncEngine: SyncEngine? = null,
    private val authSession: AuthSession? = null,
) {
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _openNote = MutableStateFlow<Note?>(null)
    val openNote: StateFlow<Note?> = _openNote.asStateFlow()

    private val _backlinks = MutableStateFlow<List<Note>>(emptyList())
    val backlinks: StateFlow<List<Note>> = _backlinks.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Note>>(emptyList())
    val searchResults: StateFlow<List<Note>> = _searchResults.asStateFlow()

    init { refresh() }

    fun refresh() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { NoteRepository.list() }
            _notes.value = list
        }
    }

    fun open(id: String) {
        scope.launch {
            val n = withContext(Dispatchers.IO) { NoteRepository.get(id) }
            _openNote.value = n
            if (n != null) refreshBacklinks(n)
        }
    }

    /** Open a note by exact (case-insensitive) title match. Used by chat [[link]] clicks. */
    fun openByTitle(title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        scope.launch {
            val all = withContext(Dispatchers.IO) { NoteRepository.list() }
            val match = all.firstOrNull { it.title.equals(t, ignoreCase = true) }
            if (match != null) {
                _openNote.value = match
                refreshBacklinks(match)
            } else {
                // No match — create a stub note with the title
                val created = withContext(Dispatchers.IO) { NoteRepository.create(t, "") }
                refresh()
                _openNote.value = created
                refreshBacklinks(created)
            }
        }
    }

    fun close() {
        _openNote.value = null
        _backlinks.value = emptyList()
    }

    private fun refreshBacklinks(note: Note) {
        scope.launch {
            val bl = withContext(Dispatchers.IO) { NoteRepository.backlinksFor(note.title) }
            _backlinks.value = bl
        }
    }

    fun createNote() {
        scope.launch {
            val n = withContext(Dispatchers.IO) { NoteRepository.create("Untitled", "") }
            refresh()
            _openNote.value = n
            enqueueSync(n)
        }
    }

    fun createFromChat(title: String, body: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        scope.launch {
            val existing = _notes.value.firstOrNull { it.title.equals(t, ignoreCase = true) }
            if (existing != null) return@launch
            val n = withContext(Dispatchers.IO) { NoteRepository.create(t, body) }
            refresh()
            enqueueSync(n)
        }
    }

    fun updateNote(id: String, title: String, body: String) {
        scope.launch {
            withContext(Dispatchers.IO) { NoteRepository.update(id, title, body) }
            val n = withContext(Dispatchers.IO) { NoteRepository.get(id) }
            _openNote.value = n
            refresh()
            if (n != null) { enqueueSync(n); refreshBacklinks(n) }
        }
    }

    fun deleteNote(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { NoteRepository.delete(id) }
            if (_openNote.value?.id == id) _openNote.value = null
            refresh()
        }
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
        scope.launch {
            val results = withContext(Dispatchers.IO) { NoteRepository.search(q) }
            _searchResults.value = results
        }
    }

    /**
     * Push the just-saved note through the sync pipeline. We don't await —
     * the UI already shows the local change; sync happens best-effort.
     *
     * Reads the password from the in-memory PasswordCache (set by the
     * unlock dialog after login). If the user skipped unlock, sync is a
     * no-op for this session.
     */
    private fun enqueueSync(note: Note) {
        val engine = syncEngine ?: return
        val password = PasswordCache.get() ?: return
        try {
            engine.enqueuePush(note, password)
        } finally {
            password.fill('\u0000')
        }
    }

    fun shutdown() { scope.cancel() }
}
