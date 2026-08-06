// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.domain.service.client

import org.skopeo.domain.model.ApiKeyEnvironment
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32

/**
 * Generation, hashing, and format-validation for partner API keys (#225/#596).
 *
 * A key looks like `skopeo_live_<random><checksum>`: an environment prefix (self-identifying), a
 * high-entropy random body, and a short CRC32 checksum so a mistyped key can be rejected offline
 * before any DB lookup. The plaintext is returned to the admin exactly once; only its SHA-256 hash is
 * persisted. A fast hash is correct here — the security comes from the key's entropy, not hash slowness
 * (a slow adaptive hash would only add latency on every authenticated request).
 */
internal object ApiKeyCrypto {
    // Crockford base32 (no I/L/O/U — copy/paste friendly), matching ClaimCodeCrypto.
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val RADIX = 32
    private const val BODY_LENGTH = 36
    private const val CHECKSUM_LENGTH = 6
    private const val PREFIX_DISPLAY_CHARS = 6
    private val random = SecureRandom()

    /** A freshly-generated key: the one-time [plaintext], its [hash], and the non-secret [displayPrefix]. */
    data class GeneratedKey(
        val plaintext: String,
        val hash: String,
        val displayPrefix: String,
    )

    /** Generate a new key for [environment]. */
    fun generate(environment: ApiKeyEnvironment): GeneratedKey {
        val body = (1..BODY_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString(separator = "")
        val withoutChecksum = environment.prefix + body
        val plaintext = withoutChecksum + checksum(input = withoutChecksum)
        return GeneratedKey(
            plaintext = plaintext,
            hash = hash(plaintext = plaintext),
            displayPrefix = environment.prefix + body.take(n = PREFIX_DISPLAY_CHARS),
        )
    }

    /** The SHA-256 hex of [plaintext] — the only form persisted. Case-sensitive (the body is high-entropy). */
    fun hash(plaintext: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(plaintext.trim().toByteArray(charset = Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * A cheap offline check that [raw] has a known prefix and a matching checksum — lets the resolver
     * reject a malformed/mistyped key without a DB round trip. Not a security check (the hash lookup is).
     */
    fun looksValid(raw: String): Boolean {
        val trimmed = raw.trim()
        val environment = ApiKeyEnvironment.entries.firstOrNull { trimmed.startsWith(prefix = it.prefix) }
        if (environment == null || trimmed.length <= environment.prefix.length + CHECKSUM_LENGTH) return false
        val withoutChecksum = trimmed.dropLast(n = CHECKSUM_LENGTH)
        val provided = trimmed.takeLast(n = CHECKSUM_LENGTH)
        val body = withoutChecksum.removePrefix(prefix = environment.prefix)
        return body.isNotEmpty() && body.all { it in ALPHABET } && checksum(input = withoutChecksum) == provided
    }

    /** A [CHECKSUM_LENGTH]-char base32 rendering of the CRC32 of [input]. */
    private fun checksum(input: String): String {
        var value = CRC32().apply { update(input.toByteArray(charset = Charsets.UTF_8)) }.value
        val builder = StringBuilder()
        repeat(times = CHECKSUM_LENGTH) {
            builder.append(ALPHABET[(value % RADIX).toInt()])
            value /= RADIX
        }
        return builder.toString()
    }
}
