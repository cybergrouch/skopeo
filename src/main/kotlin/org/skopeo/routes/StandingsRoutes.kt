// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.standings.toResponse
import org.skopeo.service.standings.StandingsService

/**
 * Per-band "Ranking Race" standings (issue #113), readable by any signed-in player. Interim and
 * ratings-derived; the route is thin.
 */
fun Application.configureStandingsRoutes(service: StandingsService = StandingsService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            get(path = "/api/v1/standings") {
                respondMappingErrors {
                    call.respond(status = HttpStatusCode.OK, message = service.standings().map { it.toResponse() })
                }
            }
        }
    }
}
