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
import org.skopeo.dto.rating.toResponse
import org.skopeo.model.Rating
import org.skopeo.service.rating.RatingCalculationService
import org.skopeo.service.rating.RatingService
import java.math.BigDecimal

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
                    ) { page -> call.respond(status = HttpStatusCode.OK, message = page.toResponse()) }
                }
            }
            // Calculation trigger (ADMINISTRATOR). dryRun defaults true; an empty body is a dry run.
            post(path = "/api/v1/ratings/calculations") {
                respondMappingErrors {
                    // No/unparseable body → a dry run (the safe default; only an explicit
                    // {"dryRun": false} commits).
                    val request = runCatching { call.receiveNullable<CalculationRequest>() }.getOrNull() ?: CalculationRequest()
                    respondEither(result = calculation.calculate(token = verifiedToken(), dryRun = request.dryRun)) { outcome ->
                        call.respond(status = HttpStatusCode.OK, message = outcome.toResponse())
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
            respondEither(result = service.getRatings(token = verifiedToken(), userId = uuidParam(name = "userId"))) { list ->
                call.respond(status = HttpStatusCode.OK, message = list.map { it.toResponse() })
            }
        }
    }
    get(path = "/rating-history") {
        respondMappingErrors {
            respondEither(result = service.getHistory(token = verifiedToken(), userId = uuidParam(name = "userId"))) { history ->
                call.respond(status = HttpStatusCode.OK, message = history.map { it.toResponse() })
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
                        value = validatedRating(raw = request.value),
                        confidence = validatedConfidence(raw = request.confidence),
                    ),
            ) { rating -> call.respond(status = HttpStatusCode.OK, message = rating.toResponse()) }
        }
    }
}

/** Validate the NTRP value at the boundary (#116): a valid number in the 1.0–7.0 range, or a 400. */
private fun validatedRating(raw: String): BigDecimal {
    Rating.fromValue(value = raw)
    return BigDecimal(raw)
}

/** Validate confidence at the boundary (#116): absent, or a number in [0, 1]; otherwise a 400. */
private fun validatedConfidence(raw: String?): BigDecimal? {
    val value = raw?.let { BigDecimal(it) } ?: return null
    require(value = value in BigDecimal.ZERO..BigDecimal.ONE) { "confidence must be between 0 and 1" }
    return value
}
