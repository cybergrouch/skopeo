// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

/**
 * The MDC keys permitted to reach a log sink (#806).
 *
 * **Why an allowlist rather than a convention.** MDC is not a scratchpad — it is a publication channel.
 * Every appender forwards it: the JSON encoder writes it as top-level fields, and an error-tracking
 * appender (#810) attaches it to every event as searchable tags. So a stray `MDC.put("email", …)`
 * anywhere in the codebase would publish that value on every subsequent line of the request, to every
 * sink, with nothing failing.
 *
 * `logback.xml` enforces this list at the encoder via `<includeMdcKeyName>`, so an unlisted key is
 * dropped rather than trusted, and [org.skopeo.common.logging.LogFields] is the single source of truth —
 * a test asserts the two agree.
 *
 * **The limit of that enforcement, stated plainly.** The encoder allowlist protects the *log* sink only.
 * An error-tracking appender reads the MDC map directly and never passes through this encoder, so it
 * would see an unlisted key. The allowlist is therefore defence in depth; the actual control is not
 * putting personal data in the MDC in the first place. That is why this list is short and why adding to
 * it should be a deliberate decision rather than a convenience.
 */
object LogFields {
    /** Correlates every line of one request, and is echoed to the caller (#805). */
    const val REQUEST_ID: String = "requestId"

    /** Cloud Logging's trace link (#804). Qualified `projects/<id>/traces/<id>`. */
    const val TRACE: String = "logging.googleapis.com/trace"

    /**
     * The complete set. Anything not here is dropped by the encoder.
     *
     * Note what is deliberately absent: no user id, no email, no Firebase UID, no display name. A
     * caller's identity is reachable from the request id via the access line if it is ever needed, which
     * keeps identity out of every log line by default rather than in it by default.
     */
    val ALLOWED_MDC_KEYS: Set<String> = setOf(REQUEST_ID, TRACE)
}
