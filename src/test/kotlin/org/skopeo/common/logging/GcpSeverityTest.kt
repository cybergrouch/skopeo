// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import ch.qos.logback.classic.Level
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class GcpSeverityTest {
    @Test
    fun `WARN maps to WARNING, the name Cloud Logging actually recognises`() {
        // The one that matters: WARN is not a LogSeverity, so an unmapped value degrades to DEFAULT and
        // sinks every warning below INFO — "everything at WARNING or above" would miss all of them.
        gcpSeverityOf(level = Level.WARN) shouldBe "WARNING"
    }

    @Test
    fun `TRACE maps to DEBUG because DEBUG is the GCP floor`() {
        gcpSeverityOf(level = Level.TRACE) shouldBe "DEBUG"
    }

    @Test
    fun `the levels that map by identity are left alone`() {
        gcpSeverityOf(level = Level.DEBUG) shouldBe "DEBUG"
        gcpSeverityOf(level = Level.INFO) shouldBe "INFO"
        gcpSeverityOf(level = Level.ERROR) shouldBe "ERROR"
    }

    @Test
    fun `a level GCP cannot name falls back to DEFAULT rather than leaking a Logback name`() {
        gcpSeverityOf(level = Level.OFF) shouldBe "DEFAULT"
        gcpSeverityOf(level = Level.ALL) shouldBe "DEFAULT"
    }
}
