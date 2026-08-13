// SPDX-License-Identifier: Apache-2.0
// Pulse — Auth session. Persists JWT token + user to a properties file under
// ~/.pulse/auth.properties (plain for now; v2 wraps in DPAPI / Keychain / libsecret).
// State is exposed via StateFlow so Compose recomposes when login/logout happens.
package com.pulseteam.desktop.data.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class Session(val token: String, val user: User)

class AuthSession(
    private val authFile: Path = Path.of(System.getProperty("user.home"), ".pulse", "auth.properties"),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow<Session?>(null)
    val state: StateFlow<Session?> = _state.asStateFlow()

    init { loadFromDisk() }

    fun login(session: Session) {
        _state.value = session
        scope.launch { persist(session) }
    }

    fun logout() {
        _state.value = null
        scope.launch { clear() }
    }

    val isAuthenticated: Boolean get() = _state.value != null
    val token: String? get() = _state.value?.token
    val user: User? get() = _state.value?.user

    private fun loadFromDisk() {
        scope.launch {
            val s = withContext(Dispatchers.IO) {
                if (!Files.exists(authFile)) null
                else runCatching {
                    val p = Properties()
                    Files.newInputStream(authFile).use { p.load(it) }
                    val token = p.getProperty("token")
                    val id = p.getProperty("user.id")
                    val email = p.getProperty("user.email")
                    if (token.isNullOrBlank() || id.isNullOrBlank() || email.isNullOrBlank()) null
                    else Session(token, User(id, email))
                }.getOrNull()
            }
            if (s != null) _state.value = s
        }
    }

    private suspend fun persist(session: Session) = withContext(Dispatchers.IO) {
        Files.createDirectories(authFile.parent)
        val p = Properties()
        p.setProperty("token", session.token)
        p.setProperty("user.id", session.user.id)
        p.setProperty("user.email", session.user.email)
        Files.newOutputStream(authFile).use { p.store(it, "Pulse auth session") }
    }

    private suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { Files.deleteIfExists(authFile) }
    }
}
