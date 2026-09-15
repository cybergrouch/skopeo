// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.skopeo.testsupport.uniqueViolation
import java.sql.SQLException

/**
 * The unit-level half of #992: what may be said about a database failure, and what must not be repeated.
 *
 * `PiiLeakTest` owns the end-to-end assertion (nothing personal in an encoded log line). These tests pin
 * the two properties that assertion depends on but cannot show on its own — that the summary is built
 * only from structured server fields, and that the redacted copy really is free of `SQLException`, which
 * is what stops [SqlExceptionRedactingFilter] from re-entering itself forever.
 */
class SqlErrorRedactionTest {
    private companion object {
        const val ADDRESS = "someone@example.invalid"
        const val CONSTRAINT = "uq_contact_verified_value"

        fun violation() =
            uniqueViolation(
                constraint = CONSTRAINT,
                table = "contact_information",
                detail = "Key (contact_type, value)=(EMAIL, $ADDRESS) already exists.",
            )
    }

    @Test
    fun `the driver composes a message that quotes the offending value`() {
        // Not an assertion about our code — an assertion about the premise. If pgjdbc ever stopped
        // putting `Detail:` in the message, the rest of this file would be guarding nothing, and a
        // silently-passing guard is worse than none.
        val message = violation().message.orEmpty()

        message.contains(other = ADDRESS) shouldBe true
        message.contains(other = CONSTRAINT) shouldBe true
    }

    @Test
    fun `the facts name the constraint and the SQLSTATE and quote no value`() {
        val facts = sqlFailureFacts(throwable = violation())

        facts.shouldBeInstanceOf<SqlFailureFacts>()
        facts.constraint shouldBe CONSTRAINT
        facts.sqlState shouldBe "23505"
        facts.table shouldBe "contact_information"
        facts.describe().contains(other = ADDRESS) shouldBe false
    }

    @Test
    fun `the facts are found through a wrapper, which is how a database exception usually arrives`() {
        // Exposed wraps the driver's exception, and its own message is `cause.toString()` — the same
        // leaking sentence one level up. Looking only at the throwable handed to the log site would miss
        // both the facts and the leak.
        val wrapped = SaveFailed(message = "saving the contact failed", cause = violation())

        sqlFailureFacts(throwable = wrapped)?.constraint shouldBe CONSTRAINT
    }

    @Test
    fun `a throwable with no database exception in it is reported and logged unchanged`() {
        val ordinary = SaveFailed(message = "nothing to do with SQL")

        sqlFailureFacts(throwable = ordinary).shouldBeNull()
        // Identity, not a copy: every other failure in the codebase must keep its own class and message,
        // and Cloud Error Reporting groups on them.
        redactedForLogging(throwable = ordinary) shouldBeSameInstanceAs ordinary
    }

    @Test
    fun `the redacted copy keeps the frames and the constraint and drops the driver's sentence`() {
        val original = SaveFailed(message = "saving the contact failed", cause = violation())

        val redacted = redactedForLogging(throwable = original)

        redacted.shouldBeInstanceOf<RedactedSqlException>()
        redacted.stackTrace.toList() shouldContainExactly original.stackTrace.toList()
        // Our own wording survives on the links we authored; the driver's does not.
        redacted.message.orEmpty().contains(other = "saving the contact failed") shouldBe true
        val driverLink = redacted.cause
        driverLink.shouldBeInstanceOf<RedactedSqlException>()
        driverLink.message.orEmpty().contains(other = CONSTRAINT) shouldBe true
        driverLink.message.orEmpty().contains(other = "23505") shouldBe true
        driverLink.message.orEmpty().contains(other = ADDRESS) shouldBe false
        driverLink.message.orEmpty().contains(other = "duplicate key value") shouldBe false
        // The class name is kept as text so the trace still reads naturally.
        driverLink.message.orEmpty().contains(other = "org.postgresql.util.PSQLException") shouldBe true
    }

    @Test
    fun `a wrapper that repeats the driver's message loses it too`() {
        // Flyway does exactly this: `FlywaySqlException` embeds the SQLException's message verbatim in
        // its own. Keeping a wrapper's message because a human or a library wrote it would hand the
        // value straight back, so the check is an exact substring match against what is being suppressed.
        val driver = violation()
        val quoting = SaveFailed(message = "migration failed: ${driver.message}", cause = driver)

        val redacted = redactedForLogging(throwable = quoting)

        redacted.message.orEmpty().contains(other = ADDRESS) shouldBe false
        redacted.message.orEmpty().contains(other = "migration failed") shouldBe false
    }

    @Test
    fun `a failure with no server error message still reports its type and SQLSTATE`() {
        // A connection-level failure carries no ErrorResponse, so there is nothing structured to read
        // beyond the SQLSTATE. It should still say that much: saying nothing is how #989 happened.
        val facts = sqlFailureFacts(throwable = SQLException("connection refused", "08006"))

        facts.shouldBeInstanceOf<SqlFailureFacts>()
        facts.constraint.shouldBeNull()
        facts.describe() shouldBe "type=SQLException sqlstate=08006"
    }

    @Test
    fun `no link of the redacted copy is still a SQLException`() {
        // The property the turbo filter's termination rests on, asserted directly rather than inferred.
        val redacted = redactedForLogging(throwable = SaveFailed(message = "wrapper", cause = violation()))

        generateSequence(seed = redacted) { it.cause }.none { it is SQLException } shouldBe true
    }

    @Test
    fun `a self-referencing cause does not hang the logging thread`() {
        // A cyclic cause chain is legal to construct, and an unbounded walk inside a log call would hang
        // the caller rather than fail visibly.
        val looping = SQLException("outer", "23505")
        looping.initCause(SQLException("inner", "23505").apply { initCause(looping) })

        sqlFailureFacts(throwable = looping)?.sqlState shouldBe "23505"
        generateSequence(seed = redactedForLogging(throwable = looping)) { it.cause }.count() shouldBe 2
    }
}

/**
 * A Kotlin-declared wrapper, so its constructor arguments can be named per the repo's `NamedArguments`
 * rule and so the tests read as the real shape: a database exception reaches a log site inside something
 * the application threw at least as often as it reaches one bare.
 */
private class SaveFailed(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
