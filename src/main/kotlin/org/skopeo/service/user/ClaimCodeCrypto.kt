// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.service.user

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Secret claim-code generation + hashing for placeholder accounts (#496). The backend generates a
 * cryptographically-random plaintext code (never admin-chosen), stores only its SHA-256 hash, and
 * returns the plaintext once. The plaintext is unguessable and distinct from the visible public code.
 *
 * The code is Crockford-base32 (no I/L/O/U — copy/paste-friendly), [CODE_LENGTH] chars drawn uniformly
 * from `SecureRandom` — ~100 bits, far beyond brute-forcing over a one-time, expiring code.
 */
internal object ClaimCodeCrypto {
    private const val CODE_LENGTH = 20
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    /** A fresh, cryptographically-random plaintext claim code. */
    fun generate(): String = (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString(separator = "")

    /** The SHA-256 hex of [plaintext] — the only form persisted. Case is normalized (uppercased) first. */
    fun hash(plaintext: String): String {
        val normalized = plaintext.trim().uppercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(charset = Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
