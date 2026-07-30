// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.rating.ApproveRatingRequestRequest
import org.skopeo.dto.rating.CreateRatingRequestRequest
import org.skopeo.dto.rating.DenyRatingRequestRequest
import org.skopeo.service.rating.RatingRequestService

/**
 * Re-rate requests (issue #140). A player creates a request and reads their own (`/me`); a RATER or
 * ADMINISTRATOR lists and approves/denies them (enforced in [RatingRequestService]).
 */
fun Application.configureRatingRequestRoutes(service: RatingRequestService = RatingRequestService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/rating-requests") {
                playerEndpoints(service = service)
                raterEndpoints(service = service)
            }
        }
    }
}

private fun Route.playerEndpoints(service: RatingRequestService) {
    post {
        respondMappingErrors {
            val request = call.receive<CreateRatingRequestRequest>()
            respondEither(result = service.create(token = verifiedToken(), justification = request.justification)) { created ->
                call.respond(status = HttpStatusCode.Created, message = created)
            }
        }
    }
    get(path = "/me") {
        respondMappingErrors {
            respondEither(result = service.mine(token = verifiedToken())) { mine ->
                if (mine == null) {
                    call.respond(status = HttpStatusCode.NoContent, message = "")
                } else {
                    call.respond(status = HttpStatusCode.OK, message = mine)
                }
            }
        }
    }
}

private fun Route.raterEndpoints(service: RatingRequestService) {
    get {
        respondMappingErrors {
            val params = call.request.queryParameters
            respondEither(
                result =
                    service.list(
                        token = verifiedToken(),
                        limit = params["limit"]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE,
                        offset = params["offset"]?.toIntOrNull() ?: 0,
                        statusRaw = params["status"],
                    ),
            ) { page -> call.respond(status = HttpStatusCode.OK, message = page) }
        }
    }
    post(path = "/{id}/approve") {
        respondMappingErrors {
            val body = call.receive<ApproveRatingRequestRequest>()
            respondEither(
                result =
                    service.approve(token = verifiedToken(), id = uuidParam(name = "id"), newRatingRaw = body.rating),
            ) { request -> call.respond(status = HttpStatusCode.OK, message = request) }
        }
    }
    post(path = "/{id}/deny") {
        respondMappingErrors {
            val body = call.receive<DenyRatingRequestRequest>()
            respondEither(
                result = service.deny(token = verifiedToken(), id = uuidParam(name = "id"), reason = body.reason),
            ) { request -> call.respond(status = HttpStatusCode.OK, message = request) }
        }
    }
}

private const val DEFAULT_PAGE_SIZE = 20
