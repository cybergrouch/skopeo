// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import org.postgresql.util.PSQLException
import java.sql.SQLException

/**
 * What it is safe to say about a database failure (#992).
 *
 * Every field here is an **identifier the server reports in a structured error field**: the constraint
 * (for a unique violation on `contact_information`, the index name `uq_contact_verified_value`), the
 * table, the column, the C routine that raised it, and the SQLSTATE. None of them is a *value*.
 *
 * The fields that carry values are deliberately absent and must stay absent. Postgres puts the offending
 * tuple in `Detail:` — `Key (contact_type, value)=(EMAIL, someone@example.com) already exists` — and
 * `contact_information.value` is the email address or phone number. `Hint:` and the `Failing row
 * contains (…)` text of a NOT NULL violation are the same hazard. Adding any of them to this record
 * would put the value straight back into every line that prints it.
 */
data class SqlFailureFacts(
    val type: String,
    val sqlState: String?,
    val constraint: String?,
    val table: String?,
    val column: String?,
    val routine: String?,
) {
    /**
     * One line, composed by us from the structured fields above.
     *
     * Composed rather than quoted on purpose: the point of this whole file is that no string authored by
     * the driver is ever copied into a log message, so the summary is built field by field from values we
     * have individually judged safe.
     */
    fun describe(): String =
        listOfNotNull(
            "type=$type",
            sqlState?.let { "sqlstate=$it" },
            constraint?.let { "constraint=$it" },
            table?.let { "table=$it" },
            column?.let { "column=$it" },
            routine?.let { "routine=$it" },
        ).joinToString(separator = " ")
}

/**
 * A stand-in for a database exception in a log event: same stack frames, no driver-composed text.
 *
 * Logback renders an exception as `className: message` per level of the cause chain, so simply *not*
 * logging the message is not enough — the throwable itself has to be replaced before it reaches an
 * appender. The original class name is kept inside [message] so the trace still reads naturally; what is
 * dropped is the driver's own words.
 */
class RedactedSqlException(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)

/** Marks where the driver's own sentence used to be, so a reader knows something was removed on purpose. */
private const val WITHHELD = "[message withheld, #992]"

/**
 * The cause chain, guarded against a self-referencing or cyclic `cause` (legal to construct, and an
 * unbounded walk here would hang the logging thread rather than fail visibly).
 *
 * Materialized rather than left lazy because callers walk it more than once, and a `Sequence` carrying
 * the cycle guard's `seen` set in its closure is exhausted after the first pass.
 */
private fun chainOf(throwable: Throwable?): List<Throwable> {
    val seen = mutableSetOf<Throwable>()
    return generateSequence(seed = throwable) { it.cause }.takeWhile { seen.add(element = it) }.toList()
}

/**
 * The safe facts about [throwable] if a database exception is anywhere in its cause chain, else null.
 *
 * The chain matters: a `SQLException` reaches a log site wrapped at least as often as it reaches one
 * bare (Exposed wraps the driver's `PSQLException` in an `ExposedSQLException`, whose own `message` is
 * `cause.toString()` — the same leaking sentence, one level up). A [PSQLException] is preferred over any
 * other link because it is the only one carrying the server's structured fields.
 */
fun sqlFailureFacts(throwable: Throwable?): SqlFailureFacts? {
    val chain = chainOf(throwable = throwable)
    val database = chain.filterIsInstance<PSQLException>().firstOrNull() ?: chain.filterIsInstance<SQLException>().firstOrNull()
    return database?.let { factsOf(exception = it) }
}

/** The facts carried by one link, without consulting its causes (each link in a redacted copy describes itself). */
private fun factsOf(exception: SQLException): SqlFailureFacts {
    // getServerErrorMessage() is the parsed ErrorResponse — field-addressable, so we can take the names
    // and leave the values. Anything else (a connection failure, a driver-side error) has the SQLSTATE
    // and nothing more, which is still worth saying.
    val server = (exception as? PSQLException)?.serverErrorMessage
    return SqlFailureFacts(
        type = exception.javaClass.simpleName,
        sqlState = (server?.sqlState ?: exception.sqlState)?.ifBlank { null },
        constraint = server?.constraint,
        table = server?.table,
        column = server?.column,
        routine = server?.routine,
    )
}

/**
 * [throwable] as it may be attached to a log event: unchanged when no database exception is involved, and
 * otherwise a copy of the whole chain with every driver-composed message replaced by [SqlFailureFacts].
 *
 * **Why a copy of the chain rather than a single wrapper.** A `Throwable`'s cause is fixed at
 * construction, so swapping out one link means rebuilding every link above it. Each copy keeps the
 * original's stack frames, which is the part worth keeping — *where* the statement was issued is exactly
 * what a constraint violation should tell you, and #989 is the cautionary tale of a constraint violation
 * that was logged nowhere and got misdiagnosed as something else entirely.
 *
 * A non-SQL link keeps its own message **unless that message quotes the driver's**. The exception to the
 * exception is not hypothetical: Flyway's `FlywaySqlException` embeds the `SQLException`'s message
 * verbatim in its own ("Message : ERROR: duplicate key … Detail: …"), so trusting a wrapper because we
 * or a library wrote it would hand the value straight back. The check is an exact substring match against
 * the very strings being suppressed — no pattern to keep in step with Postgres' wording. Suppressed
 * exceptions are dropped rather than copied: nothing here needs them, and dropping cannot leak.
 *
 * The result is guaranteed to contain no `SQLException`, which is what makes [SqlExceptionRedactingFilter]
 * safe to re-enter: the sanitized event it emits can never match its own trigger.
 */
fun redactedForLogging(throwable: Throwable): Throwable {
    val chain = chainOf(throwable = throwable)
    if (chain.none { it is SQLException }) return throwable

    val quoted = quotableText(chain = chain)
    var redacted: Throwable? = null
    for (link in chain.asReversed()) {
        redacted = copyOf(link = link, cause = redacted, quoted = quoted)
    }
    return redacted ?: throwable
}

/**
 * Below this length a fragment is too generic to match on: a short `Hint:` would blank out unrelated
 * wrapper messages that merely happen to contain the same few characters.
 */
private const val SHORTEST_MATCHABLE_FRAGMENT = 8

/** Every piece of driver-composed text in [chain] — what a wrapper must not be caught repeating. */
private fun quotableText(chain: List<Throwable>): List<String> =
    chain
        .filterIsInstance<SQLException>()
        .flatMap { link ->
            val server = (link as? PSQLException)?.serverErrorMessage
            listOfNotNull(link.message, server?.detail, server?.hint, server?.message)
        }.filter { it.length >= SHORTEST_MATCHABLE_FRAGMENT }

private fun copyOf(
    link: Throwable,
    cause: Throwable?,
    quoted: List<String>,
): Throwable {
    val name = link.javaClass.name
    val message = link.message
    val header =
        when {
            link is SQLException -> "$name $WITHHELD ${factsOf(exception = link).describe()}"
            message == null -> name
            quoted.any { message.contains(other = it) } -> "$name $WITHHELD"
            else -> "$name: $message"
        }
    return RedactedSqlException(message = header, cause = cause).apply { stackTrace = link.stackTrace }
}
