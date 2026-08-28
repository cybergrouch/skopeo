// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * Logback level → Cloud Logging `severity` (#751).
 *
 * Cloud Run parses a JSON line on stdout and reads its `severity` field, but only against
 * [Google's LogSeverity enum](https://cloud.google.com/logging/docs/reference/v2/rest/v2/LogEntry#logseverity)
 * — `DEBUG, INFO, NOTICE, WARNING, ERROR, CRITICAL, ALERT, EMERGENCY`. Logback's own names are *almost*
 * the same, and that is the trap: four of the five map by identity, so a naive `%level` looks correct in
 * local output and in most of Cloud Logging.
 *
 * The two that do not:
 * - **`WARN` is not a LogSeverity.** Unmapped values fall back to `DEFAULT`, so every warning sinks below
 *   `INFO` in the severity ordering and "show me anything at WARNING or above" silently misses all of them.
 * - **`TRACE` is not one either** — GCP's floor is `DEBUG`.
 *
 * A converter rather than a `%replace(%replace(...))` chain in `logback.xml`: the mapping is the part
 * most likely to be silently wrong, so it is worth having somewhere a test can address it directly.
 */
fun gcpSeverityOf(level: Level): String =
    when (level.toInt()) {
        Level.TRACE_INT, Level.DEBUG_INT -> "DEBUG"
        Level.INFO_INT -> "INFO"
        Level.WARN_INT -> "WARNING"
        Level.ERROR_INT -> "ERROR"
        // Logback's OFF/ALL sentinels reach here only from a misconfiguration; DEFAULT is GCP's
        // "unspecified" and sorts below DEBUG, which is the right place for a level we cannot name.
        else -> "DEFAULT"
    }

/** Registered in `logback.xml` as the `%gcpSeverity` conversion word. */
class GcpSeverityConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = gcpSeverityOf(level = event.level)
}
