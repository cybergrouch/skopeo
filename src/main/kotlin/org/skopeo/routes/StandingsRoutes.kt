// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.standings.toResponse
import org.skopeo.model.StandingsBand
import org.skopeo.service.standings.StandingsService

/**
 * Per-band "Ranking Race" standings (issue #113), served paged from a persisted snapshot (#220) and
 * readable by any signed-in player. `GET /api/v1/standings` returns one (band, sex) page plus the total
 * and the available selectors; `GET /api/v1/standings/me` locates the caller for jump-to-me. The route
 * stays thin — paging + selection live in [StandingsService].
 */
fun Application.configureStandingsRoutes(service: StandingsService = StandingsService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            get(path = "/api/v1/standings") {
                respondMappingErrors {
                    val params = call.request.queryParameters
                    val page =
                        service.page(
                            token = verifiedToken(),
                            band = params["band"]?.let { bandFromCode(code = it) },
                            sex = params["sex"],
                            limit = params["limit"]?.toIntOrNull(),
                            offset = params["offset"]?.toIntOrNull(),
                        )
                    call.respond(status = HttpStatusCode.OK, message = page.toResponse())
                }
            }
            get(path = "/api/v1/standings/me") {
                respondMappingErrors {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    val located = service.locateMe(token = verifiedToken(), limit = limit)
                    if (located == null) {
                        call.respond(
                            status = HttpStatusCode.NotFound,
                            message = errorBody(error = "Not found", message = "You are not in the current standings"),
                        )
                    } else {
                        call.respond(status = HttpStatusCode.OK, message = located.toResponse())
                    }
                }
            }
        }
    }
}

/** Map the `band` query code (e.g. "4.0") to its band, or a 400 when it isn't a known band code. */
private fun bandFromCode(code: String): StandingsBand =
    StandingsBand.ofCode(code = code)
        ?: throw BadRequestException(
            message = "Invalid band '$code'; expected one of ${StandingsBand.entries.joinToString { it.code }}",
        )
