// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

/** The MDC key Cloud Logging reads to associate a log line with a trace (#751). */
const val CLOUD_TRACE_MDC_KEY: String = "logging.googleapis.com/trace"

/** The header Cloud Run's load balancer sets on every inbound request. */
const val CLOUD_TRACE_HEADER: String = "X-Cloud-Trace-Context"

/** A trace id is 32 hex digits; anything else is not one and is discarded rather than propagated. */
private val TRACE_ID = Regex(pattern = "[0-9a-fA-F]{32}")

/**
 * Build the `logging.googleapis.com/trace` value from Cloud Run's inbound trace header (#751), so a log
 * line links to the request that produced it and sibling lines group together in the Logs Explorer.
 *
 * The header is `TRACE_ID/SPAN_ID;o=TRACE_TRUE`, and the field wants the fully-qualified resource name
 * `projects/<projectId>/traces/<TRACE_ID>` — a bare trace id is silently ignored by Cloud Logging, which
 * is why the project id is required rather than optional.
 *
 * Returns null when the value would be meaningless, and callers omit the field entirely rather than
 * emitting an empty string: locally there is no header, and in tests there is no project. A malformed
 * trace id is dropped for the same reason — a field Cloud Logging cannot resolve is worse than no field,
 * because it looks like a link that goes nowhere.
 */
fun cloudTraceField(
    header: String?,
    projectId: String?,
): String? {
    if (projectId.isNullOrBlank()) return null
    val traceId = header?.substringBefore(delimiter = "/")?.trim().orEmpty()
    return if (TRACE_ID.matches(input = traceId)) "projects/$projectId/traces/$traceId" else null
}
