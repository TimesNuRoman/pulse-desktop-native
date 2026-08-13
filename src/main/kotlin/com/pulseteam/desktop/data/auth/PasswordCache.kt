// SPDX-License-Identifier: Apache-2.0
// Pulse — in-memory password cache. Holds the user's password in process memory
// after they unlock the sync key. Never persisted to disk (v0.4 will route
// through OS Keychain / DPAPI / libsecret). Survives only as long as the app.
//
// Why we need this: the auth screen knows the password for one HTTP call
// to /api/auth/login. The SyncEngine needs it for every scrypt + AES key
// derivation. We could re-prompt every save; instead we cache it in RAM
// and clear on logout / app exit.
package com.pulseteam.desktop.data.auth

import java.util.concurrent.atomic.AtomicReference

object PasswordCache {
    private val ref = AtomicReference<CharArray?>(null)

    /** Set the password (zeroes any previous value first). */
    fun set(password: CharArray) {
        ref.getAndSet(password)?.fill('\u0000')
    }

    /** Returns a defensive copy. Caller must not retain longer than needed. */
    fun get(): CharArray? = ref.get()?.copyOf()

    val isUnlocked: Boolean get() = ref.get() != null

    fun clear() {
        ref.getAndSet(null)?.fill('\u0000')
    }
}
