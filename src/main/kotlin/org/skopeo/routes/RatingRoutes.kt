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
import org.skopeo.model.Level
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
            respondEither(result = service.getRatings(token = verifiedToken(), userId = uuidParam(name = "userId"))) { view ->
                call.respond(status = HttpStatusCode.OK, message = view.ratings.map { it.toResponse(revealRawValue = view.revealRawValue) })
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
                        value = resolvedRating(request = request),
                        confidence = validatedConfidence(raw = request.confidence),
                    ),
                // The setter is a RATER/ADMINISTRATOR, so echo back the exact value they just set.
            ) { rating -> call.respond(status = HttpStatusCode.OK, message = rating.toResponse(revealRawValue = true)) }
        }
    }
}

/**
 * Resolve the NTRP value to store (#206/#116). A [SetRatingRequest.band] selection (the normal path)
 * is mapped to the band MIDPOINT so initial ratings sit centered in their band; a precise
 * [SetRatingRequest.value] (the override path) is stored as-is. Exactly one must be present and in the
 * 1.0–7.0 range, otherwise a 400.
 */
private fun resolvedRating(request: SetRatingRequest): BigDecimal {
    val band = request.band
    if (band != null) {
        Rating.fromValue(value = band) // reject a non-numeric / out-of-range band with a 400
        return Level.bandMidpoint(band = BigDecimal(band))
    }
    val value = requireNotNull(value = request.value) { "a band or value is required" }
    Rating.fromValue(value = value)
    return BigDecimal(value)
}

/** Validate confidence at the boundary (#116): absent, or a number in [0, 1]; otherwise a 400. */
private fun validatedConfidence(raw: String?): BigDecimal? {
    val value = raw?.let { BigDecimal(it) } ?: return null
    require(value = value in BigDecimal.ZERO..BigDecimal.ONE) { "confidence must be between 0 and 1" }
    return value
}
