// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.standings.StandingsCalculationRequest
import org.skopeo.dto.standings.toResponse
import org.skopeo.service.standings.StandingsCalculationService

/**
 * Points-based standings recompute trigger (ADMINISTRATOR only, #146 phase 2). Mirrors the rating
 * calculation route: `dryRun` defaults to true (an empty/unparseable body is a dry run) and previews
 * with no writes; an explicit `{"dryRun": false}` publishes a POINTS snapshot. The route stays thin —
 * the recompute + persistence live in [StandingsCalculationService].
 */
fun Application.configureStandingsCalculationRoutes(service: StandingsCalculationService = StandingsCalculationService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            post(path = "/api/v1/standings/calculations") {
                respondMappingErrors {
                    // No/unparseable body → a dry run (the safe default; only an explicit false commits).
                    val request =
                        runCatching { call.receiveNullable<StandingsCalculationRequest>() }.getOrNull() ?: StandingsCalculationRequest()
                    respondEither(result = service.calculate(token = verifiedToken(), dryRun = request.dryRun)) { outcome ->
                        call.respond(status = HttpStatusCode.OK, message = outcome.toResponse())
                    }
                }
            }
        }
    }
}
