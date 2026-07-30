// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.rating.CalculationRequest
import org.skopeo.dto.rating.SetRatingRequest
import org.skopeo.service.rating.RatingCalculationService
import org.skopeo.service.rating.RatingService

private const val DEFAULT_PENDING_PAGE_SIZE = 20

/**
 * Rating & assessment API. Reads are self-or-ADMINISTRATOR; setting a rating and the
 * pending-assessment list are ADMINISTRATOR-only (enforced in [RatingService]). Routes stay thin.
 */
fun Application.configureRatingRoutes(
    service: RatingService = RatingService(),
    calculation: RatingCalculationService = RatingCalculationService(),
) {
    routing {
        authenticate(FIREBASE_AUTH) {
            // Constant path — registered alongside /users/{id}; Ktor prefers the constant segment.
            get(path = "/api/v1/users/pending-assessment") {
                respondMappingErrors {
                    val params = call.request.queryParameters
                    respondEither(
                        result =
                            service.pendingAssessment(
                                token = verifiedToken(),
                                limit = params["limit"]?.toIntOrNull() ?: DEFAULT_PENDING_PAGE_SIZE,
                                offset = params["offset"]?.toIntOrNull() ?: 0,
                            ),
                    ) { page -> call.respond(status = HttpStatusCode.OK, message = page) }
                }
            }
            // Calculation trigger (ADMINISTRATOR). dryRun defaults true; an empty body is a dry run.
            post(path = "/api/v1/ratings/calculations") {
                respondMappingErrors {
                    // No/unparseable body → a dry run (the safe default; only an explicit
                    // {"dryRun": false} commits).
                    val request = runCatching { call.receiveNullable<CalculationRequest>() }.getOrNull() ?: CalculationRequest()
                    respondEither(
                        // eventIds (#479) optionally scopes the run to a prefix of the pending timeline;
                        // null/empty keeps the all-pending behaviour.
                        result = calculation.calculate(token = verifiedToken(), dryRun = request.dryRun, eventIds = request.eventIds),
                    ) { outcome ->
                        call.respond(status = HttpStatusCode.OK, message = outcome)
                    }
                }
            }
            route(path = "/api/v1/users/{userId}") {
                ratings(service = service)
            }
        }
    }
}

private fun Route.ratings(service: RatingService) {
    get(path = "/ratings") {
        respondMappingErrors {
            respondEither(result = service.getRatings(token = verifiedToken(), userId = uuidParam(name = "userId"))) { ratings ->
                call.respond(status = HttpStatusCode.OK, message = ratings)
            }
        }
    }
    get(path = "/rating-history") {
        respondMappingErrors {
            respondEither(result = service.getHistory(token = verifiedToken(), userId = uuidParam(name = "userId"))) { history ->
                call.respond(status = HttpStatusCode.OK, message = history)
            }
        }
    }
    put(path = "/ratings") {
        respondMappingErrors {
            val request = call.receive<SetRatingRequest>()
            respondEither(
                result =
                    service.setRating(
                        token = verifiedToken(),
                        userId = uuidParam(name = "userId"),
                        band = request.band,
                        value = request.value,
                    ),
                // The setter is a RATER/ADMINISTRATOR, so echo back the exact value they just set.
            ) { rating -> call.respond(status = HttpStatusCode.OK, message = rating) }
        }
    }
}
