// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.rating.toResponse
import org.skopeo.service.user.PlayerService

// Page size used when a match-history request omits `limit` (#284) — the full-history page default.
private const val DEFAULT_MATCH_HISTORY_PAGE_SIZE = 20

/**
 * Shareable, auth-gated player profile resolved by public code (issue #61). Behind
 * [FIREBASE_AUTH] so it is visible only to logged-in users — never a public page — but open to
 * any authenticated user (no staff/self restriction); the service returns a limited public card.
 */
fun Application.configurePlayerRoutes(service: PlayerService = PlayerService()) {
    routing {
        route(path = "/api/v1/players") {
            // The public profile, match history, results, and standing are viewable anonymously (#193)
            // — none uses a token. The rating-history + points audits are token-gated (owner/admin).
            authenticate(FIREBASE_AUTH, optional = true) { publicPlayerReads(service = service) }
            authenticate(FIREBASE_AUTH) { auditedPlayerReads(service = service) }
        }
    }
}

/** The anonymously-viewable reads (#193): the public card, match history, results summary, and standing. */
private fun Route.publicPlayerReads(service: PlayerService) {
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
            val query = call.request.queryParameters
            respondEither(
                result =
                    service.matchHistory(
                        code = code,
                        limit = query["limit"]?.toIntOrNull() ?: DEFAULT_MATCH_HISTORY_PAGE_SIZE,
                        offset = query["offset"]?.toIntOrNull() ?: 0,
                        search = query["search"],
                    ),
            ) { page ->
                call.respond(status = HttpStatusCode.OK, message = page)
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
    // Band+sex rank + points (#448) — public (order/points already are, #64/#114). An unranked player
    // (unrated / no points) yields 204 No Content, which the UI renders as "unranked".
    get(path = "/{code}/standing") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.standing(code = code)) { standing ->
                if (standing == null) {
                    call.respond(status = HttpStatusCode.NoContent, message = "")
                } else {
                    call.respond(status = HttpStatusCode.OK, message = standing)
                }
            }
        }
    }
}

/** The token-gated audit reads: rating history (ADMINISTRATOR) and the active-points audit (owner-or-admin, #448). */
private fun Route.auditedPlayerReads(service: PlayerService) {
    get(path = "/{code}/rating-history") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.ratingHistory(token = verifiedToken(), code = code)) { history ->
                call.respond(status = HttpStatusCode.OK, message = history.map { it.toResponse() })
            }
        }
    }
    get(path = "/{code}/points") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.activePoints(token = verifiedToken(), code = code)) { awards ->
                call.respond(status = HttpStatusCode.OK, message = awards)
            }
        }
    }
}
