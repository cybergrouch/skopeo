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
import org.skopeo.dto.settings.SetStandingsSourceRequest
import org.skopeo.dto.settings.toResponse
import org.skopeo.service.settings.SettingsService

/**
 * The standings serving-source setting (#146). The GET is readable (so the Admin tab can show the current
 * value) while the PUT is ADMINISTRATOR-only (enforced in [SettingsService]) — flipping RATING↔POINTS from
 * the app with no DB access and no redeploy.
 */
fun Application.configureStandingsSourceRoutes(service: SettingsService = SettingsService()) {
    routing {
        route(path = "/api/v1/settings/standings-source") {
            authenticate(FIREBASE_AUTH, optional = true) {
                get {
                    respondMappingErrors {
                        call.respond(status = HttpStatusCode.OK, message = service.getStandingsSource().toResponse())
                    }
                }
            }
            authenticate(FIREBASE_AUTH) {
                put {
                    respondMappingErrors {
                        val request = call.receive<SetStandingsSourceRequest>()
                        respondEither(
                            result = service.setStandingsSource(token = verifiedToken(), source = request.source),
                        ) { value ->
                            call.respond(status = HttpStatusCode.OK, message = value.toResponse())
                        }
                    }
                }
            }
        }
    }
}
