// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

/**
 * A JDBC URL with anything credential-shaped removed, for the startup log line (#806).
 *
 * The URL is logged on every boot. Today it is clean, because the deployment supplies the password
 * separately from Secret Manager and `DATABASE_URL` carries only host and database. But a JDBC URL is a
 * *conventional* place to put credentials — `?user=…&password=…` is the documented Postgres form and the
 * obvious shortcut for anyone running this locally or in a new environment. The day someone takes that
 * shortcut, the password is in Cloud Logging for the retention period, and once an error-tracking
 * appender is attached (#810) it is also in a third party's breadcrumbs.
 *
 * So the query string is dropped wholesale rather than filtered for known credential parameter names: an
 * allowlist of "safe" parameters would need updating every time a driver adds one, and the parameters are
 * not what the log line is for. Host, port and database name — the part that answers "which database did
 * this instance actually connect to" — are kept.
 */
fun redactedJdbcUrl(url: String?): String {
    if (url.isNullOrBlank()) return "(unset)"
    val base = url.substringBefore(delimiter = "?")
    return if (base == url) url else "$base?(parameters redacted)"
}
