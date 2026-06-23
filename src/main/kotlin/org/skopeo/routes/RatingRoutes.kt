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
import org.skopeo.service.rating.RatingCalculationService
import org.skopeo.service.rating.RatingService

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
            get("/api/v1/users/pending-assessment") {
                respondMappingErrors {
                    val pending = service.pendingAssessment(verifiedToken())
                    call.respond(status = HttpStatusCode.OK, message = pending.map { it.toResponse() })
                }
            }
            // Calculation trigger (ADMINISTRATOR). dryRun defaults true; an empty body is a dry run.
            post("/api/v1/ratings/calculations") {
                respondMappingErrors {
                    // No/unparseable body → a dry run (the safe default; only an explicit
                    // {"dryRun": false} commits).
                    val request = runCatching { call.receiveNullable<CalculationRequest>() }.getOrNull() ?: CalculationRequest()
                    val outcome = calculation.calculate(token = verifiedToken(), dryRun = request.dryRun)
                    call.respond(status = HttpStatusCode.OK, message = outcome.toResponse())
                }
            }
            route("/api/v1/users/{userId}") {
                ratings(service)
            }
        }
    }
}

private fun Route.ratings(service: RatingService) {
    get("/ratings") {
        respondMappingErrors {
            val list = service.getRatings(token = verifiedToken(), userId = uuidParam("userId"))
            call.respond(status = HttpStatusCode.OK, message = list.map { it.toResponse() })
        }
    }
    get("/rating-history") {
        respondMappingErrors {
            val history = service.getHistory(token = verifiedToken(), userId = uuidParam("userId"))
            call.respond(status = HttpStatusCode.OK, message = history.map { it.toResponse() })
        }
    }
    put("/ratings") {
        respondMappingErrors {
            val request = call.receive<SetRatingRequest>()
            val rating =
                service.setRating(
                    token = verifiedToken(),
                    userId = uuidParam("userId"),
                    value = request.value,
                    confidence = request.confidence,
                )
            call.respond(status = HttpStatusCode.OK, message = rating.toResponse())
        }
    }
}
