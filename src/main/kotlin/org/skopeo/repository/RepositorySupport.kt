// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.skopeo.model.ServiceError
import java.sql.SQLException

/** PostgreSQL SQLSTATE for a unique-constraint violation. */
private const val PG_UNIQUE_VIOLATION = "23505"

/**
 * Run [block] (typically a transaction) translating a PostgreSQL unique-constraint violation into a
 * [ServiceError.Conflict] carrying [message] (issue #115). This is where DB error codes meet the typed
 * error taxonomy: every other failure is a genuine fault and is rethrown (→ 500). Keeping the
 * translation in the repository layer means services compose the `Either` without touching SQL state.
 */
internal inline fun <T> conflictAware(
    message: String,
    block: () -> T,
): Either<ServiceError, T> =
    try {
        block().right()
    } catch (e: ExposedSQLException) {
        if (isUniqueViolation(e = e)) ServiceError.Conflict(message = message).left() else throw e
    }

private fun isUniqueViolation(e: ExposedSQLException): Boolean =
    generateSequence<Throwable>(seed = e) { it.cause }.any { (it as? SQLException)?.sqlState == PG_UNIQUE_VIOLATION }
