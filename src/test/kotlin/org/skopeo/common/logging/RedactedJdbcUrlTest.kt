// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class RedactedJdbcUrlTest {
    @Test
    fun `credentials in the query string never reach the log line`() {
        val redacted =
            redactedJdbcUrl(url = "jdbc:postgresql://10.1.2.3:5432/SkopeoDb?user=dbadmin&password=hunter2&ssl=true")

        redacted shouldNotContain "hunter2"
        // Distinctive username — asserting on "postgres" would match the `jdbc:postgresql` scheme itself.
        redacted shouldNotContain "dbadmin"
        redacted shouldBe "jdbc:postgresql://10.1.2.3:5432/SkopeoDb?(parameters redacted)"
    }

    @Test
    fun `host and database survive, because that is what the line is for`() {
        // The point of logging the URL at all is "which database did this instance connect to".
        redactedJdbcUrl(url = "jdbc:postgresql://localhost:5432/SkopeoDb") shouldBe
            "jdbc:postgresql://localhost:5432/SkopeoDb"
    }

    @Test
    fun `an unset URL is legible rather than blank`() {
        redactedJdbcUrl(url = null) shouldBe "(unset)"
        redactedJdbcUrl(url = "  ") shouldBe "(unset)"
    }
}
