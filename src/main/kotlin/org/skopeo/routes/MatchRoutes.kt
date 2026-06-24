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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.match.CreateFixtureRequest
import org.skopeo.dto.match.MatchResultRequest
import org.skopeo.dto.match.MatchStateRequest
import org.skopeo.dto.match.toResponse
import org.skopeo.model.MatchQuery
import org.skopeo.service.match.MatchService

/**
 * Match fixtures & results. Create/result/disable are HOST/ADMINISTRATOR; the oversight list
 * (`?filter=…`) is ADMINISTRATOR-only; reading a match is restricted to participants/staff
 * (enforced in [MatchService]). Recording a result does not compute ratings.
 */
fun Application.configureMatchRoutes(service: MatchService = MatchService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/matches") {
                listAndCreate(service = service)
                byId(service = service)
            }
        }
    }
}

private fun Route.listAndCreate(service: MatchService) {
    get {
        respondMappingErrors {
            val view = matchQueryOf(value = call.request.queryParameters["filter"])
            val list = service.query(token = verifiedToken(), view = view)
            call.respond(status = HttpStatusCode.OK, message = list.map { it.toResponse() })
        }
    }
    post {
        respondMappingErrors {
            val request = call.receive<CreateFixtureRequest>()
            val match = service.createFixture(token = verifiedToken(), request = request)
            call.respond(status = HttpStatusCode.Created, message = match.toResponse())
        }
    }
}

private fun Route.byId(service: MatchService) {
    get(path = "/{id}") {
        respondMappingErrors {
            val match = service.getById(token = verifiedToken(), matchId = uuidParam(name = "id"))
            call.respond(status = HttpStatusCode.OK, message = match.toResponse())
        }
    }
    post(path = "/{id}/result") {
        respondMappingErrors {
            val request = call.receive<MatchResultRequest>()
            val match = service.uploadResult(token = verifiedToken(), matchId = uuidParam(name = "id"), request = request)
            call.respond(status = HttpStatusCode.OK, message = match.toResponse())
        }
    }
    put(path = "/{id}/state") {
        respondMappingErrors {
            val request = call.receive<MatchStateRequest>()
            val match = service.setActive(token = verifiedToken(), matchId = uuidParam(name = "id"), active = request.isActive)
            call.respond(status = HttpStatusCode.OK, message = match.toResponse())
        }
    }
}

private fun matchQueryOf(value: String?): MatchQuery =
    when (value) {
        "pending-calculation" -> MatchQuery.PENDING_CALCULATION
        "awaiting-results" -> MatchQuery.AWAITING_RESULTS
        else -> throw IllegalArgumentException("filter must be 'pending-calculation' or 'awaiting-results'")
    }
