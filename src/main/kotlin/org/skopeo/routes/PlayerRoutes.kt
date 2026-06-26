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
import org.skopeo.service.user.PlayerService

/**
 * Shareable, auth-gated player profile resolved by public code (issue #61). Behind
 * [FIREBASE_AUTH] so it is visible only to logged-in users — never a public page — but open to
 * any authenticated user (no staff/self restriction); the service returns a limited public card.
 */
fun Application.configurePlayerRoutes(service: PlayerService = PlayerService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/players") {
                get(path = "/{code}") {
                    respondMappingErrors {
                        val code = call.parameters["code"].orEmpty()
                        call.respond(status = HttpStatusCode.OK, message = service.publicProfile(code = code))
                    }
                }
                get(path = "/{code}/match-history") {
                    respondMappingErrors {
                        val code = call.parameters["code"].orEmpty()
                        call.respond(status = HttpStatusCode.OK, message = service.matchHistory(code = code))
                    }
                }
            }
        }
    }
}
