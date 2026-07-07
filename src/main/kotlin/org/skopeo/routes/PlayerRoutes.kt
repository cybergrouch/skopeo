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
import org.skopeo.dto.rating.toResponse
import org.skopeo.service.user.PlayerService

/**
 * Shareable, auth-gated player profile resolved by public code (issue #61). Behind
 * [FIREBASE_AUTH] so it is visible only to logged-in users — never a public page — but open to
 * any authenticated user (no staff/self restriction); the service returns a limited public card.
 */
fun Application.configurePlayerRoutes(service: PlayerService = PlayerService()) {
    routing {
        route(path = "/api/v1/players") {
            // The public profile + its match history are viewable anonymously (#193) — neither uses
            // a token. The rating-history audit view stays ADMINISTRATOR-only (required auth).
            authenticate(FIREBASE_AUTH, optional = true) {
                get(path = "/{code}") {
                    respondMappingErrors {
                        val code = call.parameters["code"].orEmpty()
                        respondEither(result = service.publicProfile(code = code)) { profile ->
                            call.respond(status = HttpStatusCode.OK, message = profile)
                        }
                    }
                }
                get(path = "/{code}/match-history") {
                    respondMappingErrors {
                        val code = call.parameters["code"].orEmpty()
                        respondEither(result = service.matchHistory(code = code)) { history ->
                            call.respond(status = HttpStatusCode.OK, message = history)
                        }
                    }
                }
                // Win–loss record over time (#276), split singles/doubles — aggregated server-side.
                get(path = "/{code}/results-summary") {
                    respondMappingErrors {
                        val code = call.parameters["code"].orEmpty()
                        respondEither(result = service.resultsSummary(code = code)) { summary ->
                            call.respond(status = HttpStatusCode.OK, message = summary)
                        }
                    }
                }
            }
            // ADMINISTRATOR only — the precise rating-history audit view for any player by code.
            authenticate(FIREBASE_AUTH) {
                get(path = "/{code}/rating-history") {
                    respondMappingErrors {
                        val code = call.parameters["code"].orEmpty()
                        respondEither(result = service.ratingHistory(token = verifiedToken(), code = code)) { history ->
                            call.respond(status = HttpStatusCode.OK, message = history.map { it.toResponse() })
                        }
                    }
                }
            }
        }
    }
}
