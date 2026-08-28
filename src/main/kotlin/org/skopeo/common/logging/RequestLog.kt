// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.common.logging

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import net.logstash.logback.marker.Markers
import org.slf4j.LoggerFactory

/** The header carrying the request id, accepted inbound and echoed on every response (#805). */
const val REQUEST_ID_HEADER: String = "X-Request-Id"

/** MDC key for the request id, so every line a request emits carries it. */
const val REQUEST_ID_MDC_KEY: String = "requestId"

/**
 * The `route` value for a call that never matched a route — a 404 from a mistyped URL, a scanner, a
 * stale QR link.
 *
 * Falling back to the raw path here would defeat the point of logging the pattern at all: unmatched
 * traffic is unbounded and attacker-controlled, so it would create a new metric series per bogus URL
 * (#809). One bucket is the useful answer — "how much traffic is hitting nothing" — and it is bounded.
 */
const val UNMATCHED_ROUTE: String = "(unmatched)"

private const val NANOS_PER_MILLI = 1_000_000L

@Suppress("TopLevelPropertyNaming") // matches the `logger` convention used across the codebase
private val accessLogger = LoggerFactory.getLogger("org.skopeo.access")

private val STARTED_AT_NANOS = AttributeKey<Long>(name = "skopeo.request.startedAtNanos")

/**
 * The matched pattern, stashed during routing.
 *
 * Necessary because the [ResponseSent] hook — the only place status and duration exist — is handed the
 * application-level call, **not** the `RoutingCall`. Reading the route there always yields
 * [UNMATCHED_ROUTE], including for requests that matched perfectly well. So the pattern is captured on
 * `RoutingCallStarted`, where the routed call is available, and read back at response time.
 */
private val MATCHED_ROUTE = AttributeKey<String>(name = "skopeo.request.matchedRoute")

/** Trailing routing selectors Ktor appends to a node's path, e.g. `/events/{code}/(method:GET)`. */
private val ROUTE_SELECTOR_SUFFIX = Regex(pattern = "/\\((?:method|authenticate|RateLimit)[^)]*\\)$")

/**
 * The matched route **pattern** for [call] — `/api/v1/matches/{id}/score-correction`, not the concrete
 * path with a UUID in it.
 *
 * This distinction is the difference between logs that can be grouped by endpoint and logs that cannot.
 * Every match id in a raw URI is a distinct string, so `route` would have unbounded cardinality: useless
 * as a log-based metric label and expensive to store (#809). It is also the string a future error
 * tracker uses as its `transaction` name, so the two have to agree or a metric cannot link to a trace.
 */
fun routePatternOf(call: ApplicationCall): String {
    val node = (call as? RoutingCall)?.route ?: return UNMATCHED_ROUTE
    return node.toString().replace(regex = ROUTE_SELECTOR_SUFFIX, replacement = "").ifBlank { "/" }
}

/**
 * One structured access line per request (#805): `method`, `route`, `status`, `durationMs`, alongside
 * the `requestId` and trace already in the MDC.
 *
 * **Why this is a plugin rather than `CallLogging`'s `format`.** Three of those four fields are
 * unavailable where `CallLogging` would put them:
 *
 * - `CallLogging`'s `mdc { }` providers resolve at call *start*, before routing has run — so the matched
 *   route does not exist yet, and neither does the status or the duration.
 * - `CallLogging`'s `format { }` runs after the response, but its result is the log *message* — a single
 *   string. Fields inside a message are not queryable and cannot be a metric label.
 *
 * So the line is emitted here, on [ResponseSent], where all four are known.
 *
 * **Why a marker rather than MDC.** MDC values are always strings. `status` and `durationMs` have to
 * stay JSON *numbers* for a Cloud Logging distribution metric to compute latency percentiles over them
 * (#809), and `Markers.appendEntries` preserves the type. `logback.xml` carries the matching
 * `<logstashMarkers/>` provider.
 */
@Suppress("TopLevelPropertyNaming") // Ktor plugin values are PascalCase by convention
val RequestLog =
    createApplicationPlugin(name = "RequestLog") {
        on(hook = CallSetup) { call ->
            call.attributes.put(key = STARTED_AT_NANOS, value = System.nanoTime())
        }

        // Captured here rather than at response time: see MATCHED_ROUTE.
        on(hook = MonitoringEvent(event = RoutingRoot.RoutingCallStarted)) { call ->
            call.attributes.put(key = MATCHED_ROUTE, value = routePatternOf(call = call))
        }

        on(hook = ResponseSent) { call ->
            val startedAt = call.attributes.getOrNull(key = STARTED_AT_NANOS)
            val fields: Map<String, Any> =
                listOfNotNull(
                    "method" to call.request.httpMethod.value,
                    "route" to (call.attributes.getOrNull(key = MATCHED_ROUTE) ?: UNMATCHED_ROUTE),
                    call.response.status()?.value?.let { "status" to it },
                    startedAt?.let { "durationMs" to (System.nanoTime() - it) / NANOS_PER_MILLI },
                ).toMap()
            // The message stays human-readable for a local `docker logs`; the fields above are what
            // Cloud Logging queries and what #809's metrics group by.
            accessLogger.info(
                Markers.appendEntries(fields),
                "{} {} -> {}",
                fields["method"],
                fields["route"],
                fields["status"] ?: "no response",
            )
        }
    }
