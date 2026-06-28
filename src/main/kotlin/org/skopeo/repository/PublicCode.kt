// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import kotlin.random.Random

/**
 * Shareable public codes — 6 chars from a Crockford-style base32 alphabet (no I/L/O/U, to avoid
 * ambiguity). Used for player codes (issue #56) and match codes (#136), and reusable for any future
 * shareable entity. [generate] retries on the rare collision via the caller's [isTaken] predicate,
 * which must run inside the surrounding transaction.
 */
internal object PublicCode {
    const val LENGTH = 6
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val MAX_TRIES = 10

    fun generate(isTaken: (String) -> Boolean): String {
        repeat(times = MAX_TRIES) {
            val code =
                (1..LENGTH)
                    .map { ALPHABET[Random.nextInt(until = ALPHABET.length)] }
                    .joinToString(separator = "")
            if (!isTaken(code)) return code
        }
        error(message = "could not generate a unique public code after $MAX_TRIES tries")
    }
}
