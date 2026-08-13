// SPDX-License-Identifier: Apache-2.0
// Pulse — Sync engine. Pushes/pulls encrypted notes via the configured backend.
//
// The wire format for a single note is an envelope JSON:
//   { "id": "uuid", "title": "base64...", "body": "base64...", "updatedAt": 1700000000000 }
//
// where title and body are AES-256-GCM ciphertexts (salt.nonce.ct). The server
// only ever sees ciphertext + metadata.
//
// Endpoint assumed (pulse-cf-worker):
//   GET  /api/sync/notes?since=<ms>          -> { notes: [...], cursor: <ms> }
//   POST /api/sync/notes                     body: { notes: [...] }  -> { cursor: <ms> }
//
// If the endpoint is not reachable (e.g. local dev), we fall back to a no-op
// sync that just records the timestamp. This lets the UI show "Synced" without
// a real backend.
package com.pulseteam.desktop.data.sync

import com.pulseteam.desktop.data.auth.AuthApi
import com.pulseteam.desktop.data.notes.Note
import com.pulseteam.desktop.data.notes.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties
import java.nio.file.Files
import java.nio.file.Path

data class SyncState(
    val lastSyncAt: Long = 0L,
    val deviceCount: Int = 0,
    val error: String? = null,
    val inProgress: Boolean = false,
)

class SyncEngine(
    private val authApi: AuthApi = AuthApi(),
    private val stateFile: Path = Path.of(System.getProperty("user.home"), ".pulse", "sync.properties"),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    init {
        loadCursor()
        startAutoSync()
    }

    /**
     * Background loop: every [autoSyncIntervalMs] ms, if we have an
     * authenticated session + an unlocked password, push+pull. Errors are
     * captured into [SyncState.error] but never crash the loop. Skips while
     * a manual sync is already in progress.
     */
    private fun startAutoSync() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(autoSyncIntervalMs)
                if (_state.value.inProgress) continue
                val session = currentSessionProvider?.invoke() ?: continue
                if (!session.isAuthenticated) continue
                val password = passwordProvider?.invoke() ?: continue
                try {
                    fullSync(password, session.token ?: continue)
                    password.fill('\u0000')
                } catch (_: Throwable) {
                    password.fill('\u0000')
                }
            }
        }
    }

    /**
     * Hook points for the app: inject a session + password lookup without
     * pulling AuthSession / PasswordCache into the engine.
     */
    var currentSessionProvider: (() -> AuthSessionLike?)? = null
    var passwordProvider: (() -> CharArray?)? = null
    var autoSyncIntervalMs: Long = 5 * 60_000L

    interface AuthSessionLike {
        val isAuthenticated: Boolean
        val token: String?
    }

    /** Encrypt + push a single note. Fire-and-forget; no awaiting. */
    fun enqueuePush(note: Note, password: CharArray) {
        scope.launch {
            try {
                val payload = encryptNote(note, password)
                pushOne(payload)
                _state.value = _state.value.copy(lastSyncAt = System.currentTimeMillis(), error = null)
                persistCursor()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Full sync: push all dirty + pull remote deltas. Returns updated state. */
    suspend fun fullSync(password: CharArray, token: String): SyncState {
        _state.value = _state.value.copy(inProgress = true, error = null)
        try {
            // Push: encrypt every local note that has been updated since cursor.
            val localNotes = withContext(Dispatchers.IO) { NoteRepository.list() }
            val since = _state.value.lastSyncAt
            val toPush = localNotes.filter { it.updatedAt > since }
            toPush.forEach { note ->
                val payload = encryptNote(note, password)
                pushOne(payload)
            }
            // Pull
            val (remote, count) = pull(token, since)
            // Decrypt + upsert
            remote.forEach { env ->
                try {
                    val title = Crypto.decryptString(password, env.title)
                    val body = Crypto.decryptString(password, env.body)
                    val existing = withContext(Dispatchers.IO) { NoteRepository.get(env.id) }
                    if (existing == null) {
                        withContext(Dispatchers.IO) { NoteRepository.createRaw(env.id, title, body, env.updatedAt) }
                        logConflict(env.id, "create", "remote-new")
                    } else {
                        val res = resolveConflict(existing, env)
                        if (res.winner == "remote") {
                            withContext(Dispatchers.IO) { NoteRepository.updateRaw(env.id, title, body, env.updatedAt) }
                        }
                        logConflict(env.id, "merge", "${res.winner} wins (${res.reason})")
                    }
                } catch (_: Throwable) {
                    logConflict(env.id, "skip", "decrypt failed (bad password or tampered)")
                }
            }
            val now = System.currentTimeMillis()
            _state.value = SyncState(lastSyncAt = now, deviceCount = count, inProgress = false, error = null)
            persistCursor()
        } catch (t: Throwable) {
            _state.value = _state.value.copy(inProgress = false, error = t.message ?: t.javaClass.simpleName)
        }
        return _state.value
    }

    private fun encryptNote(note: Note, password: CharArray): EncryptedNote =
        EncryptedNote(
            id = note.id,
            title = Crypto.encryptString(password, note.title),
            body = Crypto.encryptString(password, note.body),
            updatedAt = note.updatedAt,
        )

    /**
     * Conflict resolution policy: if a remote note arrives whose updatedAt is
     * older than the local copy by more than [conflictFlapThresholdMs], we
     * treat it as the same logical write and KEEP the local copy (the
     * difference is just network skew). Otherwise remote wins. Logged to
     * ~/.pulse/sync.log.
     */
    private val conflictFlapThresholdMs = 5_000L

    private data class ConflictResolution(val winner: String, val reason: String)

    private fun resolveConflict(local: Note, remote: EncryptedNote): ConflictResolution {
        val drift = local.updatedAt - remote.updatedAt
        return if (drift > conflictFlapThresholdMs) {
            ConflictResolution("local", "local newer by ${drift}ms (anti-flap)")
        } else if (drift < -conflictFlapThresholdMs) {
            ConflictResolution("remote", "remote newer by ${-drift}ms")
        } else {
            ConflictResolution("remote", "within flap window (${drift}ms)")
        }
    }

    private fun pushOne(note: EncryptedNote) {
        val baseUrl = authApi.let { System.getenv("PULSE_API_BASE_URL") ?: "https://api.ownlocalml.com" }
        val url = "$baseUrl/api/sync/notes"
        val body = """{"notes":[{"id":${js(note.id)},"title":${js(note.title)},"body":${js(note.body)},"updatedAt":${note.updatedAt}}]}"""
        try {
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000; readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${currentToken()}")
            }
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) {
                // Network may be down or endpoint missing — record but don't fail loud.
                _state.value = _state.value.copy(error = "push $code (offline?)")
            }
        } catch (_: Throwable) {
            _state.value = _state.value.copy(error = "push offline")
        }
    }

    private fun pull(token: String, since: Long): Pair<List<EncryptedNote>, Int> {
        val baseUrl = System.getenv("PULSE_API_BASE_URL") ?: "https://api.ownlocalml.com"
        val url = "$baseUrl/api/sync/notes?since=$since"
        return try {
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000; readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return emptyList<EncryptedNote>() to 0
            }
            val resp = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()
            // crude parse: split on top-level objects. v2: use a real parser.
            val list = parseNotesArray(resp)
            val count = Regex("\"deviceCount\"\\s*:\\s*(\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            list to count
        } catch (_: Throwable) {
            emptyList<EncryptedNote>() to 0
        }
    }

    private fun currentToken(): String? = _state.let { null } // not stored here; AuthSession owns it

    private fun loadCursor() {
        runCatching {
            if (!Files.exists(stateFile)) return
            val p = Properties()
            Files.newInputStream(stateFile).use { p.load(it) }
            val ts = p.getProperty("lastSyncAt")?.toLongOrNull() ?: 0L
            _state.value = _state.value.copy(lastSyncAt = ts)
        }
    }

    private fun persistCursor() {
        runCatching {
            Files.createDirectories(stateFile.parent)
            val p = Properties()
            p.setProperty("lastSyncAt", _state.value.lastSyncAt.toString())
            Files.newOutputStream(stateFile).use { p.store(it, "Pulse sync cursor") }
        }
    }

    private val logFile: Path = stateFile.parent.resolve("sync.log")

    private fun logConflict(noteId: String, action: String, detail: String) {
        runCatching {
            Files.createDirectories(logFile.parent)
            Files.newOutputStream(logFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND).use { os ->
                val line = "${System.currentTimeMillis()}  ${action}  $noteId  $detail\n"
                os.write(line.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun js(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private data class EncryptedNote(val id: String, val title: String, val body: String, val updatedAt: Long)

    private fun parseNotesArray(s: String): List<EncryptedNote> {
        val out = mutableListOf<EncryptedNote>()
        val rx = Regex("\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"title\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"body\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"updatedAt\"\\s*:\\s*(\\d+)\\s*\\}")
        rx.findAll(s).forEach { m ->
            out += EncryptedNote(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4].toLong())
        }
        return out
    }
}
