// SPDX-License-Identifier: Apache-2.0
// Pulse — symmetric crypto for sync. AES-256-GCM with scrypt-derived key.
// Envelope: base64(salt) + "." + base64(nonce) + "." + base64(ciphertext_with_tag)
// Salt is per-note; nonce is 12 bytes random per encrypt; key derived from
// user password + salt via scrypt(N=2^15, r=8, p=1, dkLen=32).
//
// Server only ever sees the envelope. Password never leaves device.
package com.pulseteam.desktop.data.sync

import org.bouncycastle.crypto.generators.SCrypt
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private const val N = 32768        // 2^15
    private const val R = 8
    private const val P = 1
    private const val DK_LEN = 32       // 256-bit key for AES-256
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_LEN = 128     // bits

    private val rng = SecureRandom()

    /** Derive a 32-byte AES key from password + salt using scrypt. */
    fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val pwdBytes = String(password).toByteArray(Charsets.UTF_8)
        val key = SCrypt.generate(pwdBytes, salt, N, R, P, DK_LEN)
        // Wipe the password bytes from memory ASAP.
        pwdBytes.fill(0)
        return key
    }

    /** Encrypt plaintext. Returns envelope: "salt.nonce.ct" (all base64). */
    fun encrypt(password: CharArray, plaintext: ByteArray): String {
        val salt = ByteArray(SALT_LEN).also(rng::nextBytes)
        val nonce = ByteArray(NONCE_LEN).also(rng::nextBytes)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN, nonce))
        val ct = cipher.doFinal(plaintext)
        val b64 = Base64.getEncoder()
        return "${b64.encodeToString(salt)}.${b64.encodeToString(nonce)}.${b64.encodeToString(ct)}"
    }

    /** Decrypt envelope. Throws on bad password / tampered ciphertext. */
    fun decrypt(password: CharArray, envelope: String): ByteArray {
        val parts = envelope.split(".")
        require(parts.size == 3) { "bad envelope" }
        val b64 = Base64.getDecoder()
        val salt = b64.decode(parts[0])
        val nonce = b64.decode(parts[1])
        val ct = b64.decode(parts[2])
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN, nonce))
        return cipher.doFinal(ct)
    }

    /** Convenience: encrypt UTF-8 string. */
    fun encryptString(password: CharArray, plaintext: String): String =
        encrypt(password, plaintext.toByteArray(Charsets.UTF_8))

    /** Convenience: decrypt envelope back to UTF-8 string. */
    fun decryptString(password: CharArray, envelope: String): String =
        String(decrypt(password, envelope), Charsets.UTF_8)
}
