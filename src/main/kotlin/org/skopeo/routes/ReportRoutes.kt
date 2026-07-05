// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.service.report.ReportService
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * ADMINISTRATOR-only reports (#216). `GET /api/v1/reports/band-hops?startDate=&endDate=` returns the
 * NTRP band-hop report over the range (admin gating is enforced in the service). Both dates are
 * required ISO-8601 (yyyy-MM-dd); a missing/malformed value is a 400.
 */
fun Application.configureReportRoutes(service: ReportService = ReportService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/reports") {
                get(path = "/band-hops") {
                    respondMappingErrors {
                        val params = call.request.queryParameters
                        respondEither(
                            result =
                                service.bandHops(
                                    token = verifiedToken(),
                                    startDate = parseReportDate(name = "startDate", value = params["startDate"]),
                                    endDate = parseReportDate(name = "endDate", value = params["endDate"]),
                                ),
                        ) { report -> call.respond(status = HttpStatusCode.OK, message = report) }
                    }
                }
            }
        }
    }
}

/** Parse a required ISO-8601 date query param at the boundary; a missing/bad value maps to a 400. */
private fun parseReportDate(
    name: String,
    value: String?,
): LocalDate {
    requireNotNull(value = value) { "$name is required (ISO-8601 yyyy-MM-dd)" }
    return try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Invalid $name '$value'; expected ISO-8601 (yyyy-MM-dd)", e)
    }
}
