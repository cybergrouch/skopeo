// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.testsupport

import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage

/** pgjdbc parses an ErrorResponse as NUL-terminated fields, each prefixed by its one-byte field type. */
private val FIELD_END = Char(code = 0).toString()

/** PostgreSQL SQLSTATE for a unique-constraint violation. */
private const val UNIQUE_VIOLATION = "23505"

/**
 * The exception pgjdbc raises for a unique-index violation, built from the wire-format ErrorResponse
 * rather than from a message string.
 *
 * Going through [ServerErrorMessage] matters: it means `getMessage()` is composed by the *driver*, from
 * the same fields a real server sends, which is precisely the hazard under test (#992). Hand-writing the
 * sentence in a test would prove nothing about the sentence production actually logs.
 *
 * [detail] is the `Detail:` clause — the part that quotes the offending row, and on
 * `contact_information` that means the email address or phone number.
 */
fun uniqueViolation(
    constraint: String,
    table: String,
    detail: String,
): PSQLException {
    val fields =
        listOf(
            "SERROR",
            "C$UNIQUE_VIOLATION",
            """Mduplicate key value violates unique constraint "$constraint"""",
            "D$detail",
            "t$table",
            "n$constraint",
            "R_bt_check_unique",
        )
    return PSQLException(ServerErrorMessage(fields.joinToString(separator = FIELD_END, postfix = FIELD_END)))
}
