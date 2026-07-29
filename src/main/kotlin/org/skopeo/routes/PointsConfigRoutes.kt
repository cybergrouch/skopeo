// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.mapper.settings.toResponse
import org.skopeo.model.OpenPlayPointsConfig
import org.skopeo.model.TournamentPointsConfig
import org.skopeo.service.settings.PointsConfigService

/**
 * The global, admin-configurable points schedules (#552/#553): the open-play margin-bracket table and
 * the tournament placement table (+ validity). GETs are readable by any signed-in user (the admin UI
 * loads them); PUTs are ADMINISTRATOR-only (enforced in [PointsConfigService]).
 */
fun Application.configurePointsConfigRoutes(service: PointsConfigService = PointsConfigService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/settings/points/open-play") {
                get {
                    respondMappingErrors {
                        call.respond(status = HttpStatusCode.OK, message = service.getOpenPlay().toResponse())
                    }
                }
                put {
                    respondMappingErrors {
                        val request = call.receive<OpenPlayPointsConfig>()
                        respondEither(result = service.setOpenPlay(token = verifiedToken(), config = request)) { value ->
                            call.respond(status = HttpStatusCode.OK, message = value.toResponse())
                        }
                    }
                }
            }
            route(path = "/api/v1/settings/points/tournament") {
                get {
                    respondMappingErrors {
                        call.respond(status = HttpStatusCode.OK, message = service.getTournament().toResponse())
                    }
                }
                put {
                    respondMappingErrors {
                        val request = call.receive<TournamentPointsConfig>()
                        respondEither(result = service.setTournament(token = verifiedToken(), config = request)) { value ->
                            call.respond(status = HttpStatusCode.OK, message = value.toResponse())
                        }
                    }
                }
            }
        }
    }
}
