// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/**
 * The rule that a raw database exception never reaches an appender, enforced for code we do not own (#992).
 *
 * `respondMappingErrors` can redact what *our* boundary logs, but the loudest copy of a constraint
 * violation is logged by Exposed itself, inside `handleSQLException`, before our code ever sees the
 * exception:
 *
 * ```
 * Transaction attempt #0 failed: <driver message, Detail: and all>. Statement(s): …
 * ```
 *
 * That message is pre-composed by a library, so there is no call site to fix, nothing to wrap in
 * `Redactable`, and no log level to turn down that would not also hide the failure. A `TurboFilter` is
 * the one hook that sees an event *before* it is created and can refuse it — registered in `logback.xml`,
 * it applies to every logger in the JVM, which is the property that makes this an enforcement point
 * rather than another place to remember.
 *
 * **Why the original message is dropped wholesale.** The alternative — scrubbing `Detail:` out of the
 * text — has to keep pace with Postgres' wording across versions and with every other library that
 * interpolates `cause.message`, and it fails open when it falls behind. Dropping the message and
 * rebuilding a summary from [SqlFailureFacts] fails closed: a field is either one we judged safe or it
 * is not emitted at all. Our own messages are dropped on this path too, deliberately — a log site that
 * interpolates `e.message` into its own sentence is exactly the mistake this guards, so "we wrote it"
 * cannot be treated as evidence it is clean. Code that wants to keep its wording redacts the throwable
 * itself with [redactedForLogging] and never reaches this filter.
 *
 * **Not a way to silence the failure.** The replacement event is logged at the same level, through the
 * same logger, with the constraint name, the SQLSTATE and the original stack frames. #989 is the
 * cautionary tale in the other direction: a constraint violation nobody logged got misreported as a lost
 * sequence race and cost real debugging time.
 */
class SqlExceptionRedactingFilter : TurboFilter() {
    override fun decide(
        marker: Marker?,
        logger: Logger,
        level: Level,
        format: String?,
        params: Array<out Any>?,
        t: Throwable?,
    ): FilterReply {
        // The overwhelmingly common case is an event with no throwable at all, so the cheap null check
        // comes first: this runs on every log call in the process, including ones the level will drop.
        val facts = t?.let { sqlFailureFacts(throwable = it) } ?: return FilterReply.NEUTRAL

        // Re-emitted through the *original* logger so the origin ("Exposed") is still visible in the
        // `logger` field, and at the original level so severity-based alerting is unaffected. This
        // re-enters the filter chain; it terminates because `redactedForLogging` is guaranteed to return
        // a chain with no SQLException in it, so the second pass falls out at the check above.
        // `toLocationAwareLoggerInteger`, not `toInt()`: the raw log method speaks SLF4J's level codes
        // (ERROR is 40), while logback's own are two orders of magnitude larger (ERROR is 40000) and are
        // rejected outright.
        val slf4jLevel = Level.toLocationAwareLoggerInteger(level)
        logger.log(marker, FQCN, slf4jLevel, summarize(format = format, facts = facts), null, redactedForLogging(throwable = t))
        return FilterReply.DENY
    }
}

/** Reported as the caller so logback's location awareness points here rather than into the filter chain. */
private val FQCN = SqlExceptionRedactingFilter::class.java.name

/**
 * Exposed's retry preamble. Anchored, and it captures nothing but a digit run, so the only text this can
 * ever copy out of a library-composed message is the literal words in the pattern — it is an allowlist,
 * not a scrubber. If Exposed rewords the line the match simply fails and the attempt number is omitted,
 * which is the right direction to fail in.
 */
private val TRANSACTION_ATTEMPT = Regex(pattern = """^Transaction attempt #(\d+) failed""")

private fun summarize(
    format: String?,
    facts: SqlFailureFacts,
): String {
    val attempt = format?.let { TRANSACTION_ATTEMPT.find(input = it)?.groupValues?.get(index = 1) }
    val occasion = attempt?.let { " on transaction attempt #$it" }.orEmpty()
    return "SQL failure$occasion, original message withheld because a driver message can quote column values (#992): " +
        facts.describe()
}
