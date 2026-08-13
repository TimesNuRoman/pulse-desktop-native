// SPDX-License-Identifier: Apache-2.0
// Pulse — CryptoTest. Roundtrip + tamper-detection for AES-256-GCM.
package com.pulseteam.desktop.data.sync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CryptoTest {

    @Test
    fun `encrypt then decrypt returns the original bytes`() {
        val pw = "correct-horse-battery-staple".toCharArray()
        val plaintext = "Привет, Pulse! 你好 🚀".toByteArray(Charsets.UTF_8)
        val envelope = Crypto.encrypt(pw, plaintext)
        val decoded = Crypto.decrypt(pw, envelope)
        assertArrayEquals(plaintext, decoded)
        // Sanity: envelope is base64.base64.base64
        assertEquals(3, envelope.split(".").size)
    }

    @Test
    fun `decrypt with wrong password fails`() {
        val pw1 = "right-password".toCharArray()
        val pw2 = "wrong-password".toCharArray()
        val plaintext = "secret note body".toByteArray(Charsets.UTF_8)
        val envelope = Crypto.encrypt(pw1, plaintext)
        // GCM is authenticated: a wrong key causes an AEAD BadPaddingException
        // (we catch it and rethrow as a clearer error in production).
        assertThrows(Exception::class.java) { Crypto.decrypt(pw2, envelope) }
    }

    @Test
    fun `each encrypt produces a different nonce and salt`() {
        val pw = "x".toCharArray()
        val a = Crypto.encrypt(pw, "same".toByteArray())
        val b = Crypto.encrypt(pw, "same".toByteArray())
        assertNotEquals(a, b, "Random salt+nonce must make two ciphertexts of the same plaintext differ")
    }

    @Test
    fun `tampered ciphertext fails GCM auth tag check`() {
        val pw = "x".toCharArray()
        val env = Crypto.encrypt(pw, "hello".toByteArray())
        // Flip a byte in the ciphertext portion (after the 2nd dot)
        val parts = env.split(".")
        val tampered = parts[0] + "." + parts[1] + "." + (parts[2].toByteArray().also { it[0] = (it[0].toInt() xor 0x01).toByte() }.toString(Charsets.UTF_8))
        assertThrows(Exception::class.java) { Crypto.decrypt(pw, tampered) }
    }

    @Test
    fun `string roundtrip works for unicode`() {
        val pw = "pulse-2026".toCharArray()
        val envelope = Crypto.encryptString(pw, "Hello, 世界! 🎉")
        assertEquals("Hello, 世界! 🎉", Crypto.decryptString(pw, envelope))
    }
}
