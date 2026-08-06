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
import org.skopeo.common.dto.match.CreateFixtureRequest
import org.skopeo.common.dto.match.MatchResultRequest
import org.skopeo.common.dto.match.MatchStateRequest
import org.skopeo.common.dto.match.ReorderMatchesRequest
import org.skopeo.common.dto.match.SetHandicapsRequest
import org.skopeo.domain.service.match.MatchService
import java.math.BigDecimal
import java.util.UUID

/**
 * Match fixtures & results. Create/result/disable are HOST/ADMINISTRATOR; the oversight list
 * (`?filter=…`) is ADMINISTRATOR-only; reading a match is restricted to participants/staff
 * (enforced in [MatchService]). Recording a result does not compute ratings.
 */
fun Application.configureMatchRoutes(service: MatchService = MatchService()) {
    routing {
        route(path = "/api/v1/matches") {
            // The public-by-code page is viewable anonymously (#193): a token is used if present
            // (raters/admins see precise rates) but not required.
            authenticate(FIREBASE_AUTH, optional = true) {
                publicByCode(service = service)
            }
            authenticate(FIREBASE_AUTH) {
                listAndCreate(service = service)
                upcoming(service = service)
                byId(service = service)
            }
        }
    }
}

/** The caller's own upcoming (scheduled) matches for their private profile (#251). Literal path, so it
 * takes priority over `/{id}`; authenticated so only the owner sees their fixtures. */
private fun Route.upcoming(service: MatchService) {
    get(path = "/upcoming") {
        respondMappingErrors {
            respondEither(result = service.upcomingForCaller(token = verifiedToken())) { list ->
                call.respond(status = HttpStatusCode.OK, message = list)
            }
        }
    }
}

private fun Route.listAndCreate(service: MatchService) {
    get {
        respondMappingErrors {
            val eventId = call.request.queryParameters["eventId"]?.let { parseUuid(value = it) }
            respondEither(
                result = service.query(token = verifiedToken(), filter = call.request.queryParameters["filter"], eventId = eventId),
            ) { list ->
                call.respond(status = HttpStatusCode.OK, message = list)
            }
        }
    }
    post {
        respondMappingErrors {
            val request = call.receive<CreateFixtureRequest>()
            respondEither(
                result = service.createFixture(token = verifiedToken(), request = request),
            ) { match -> call.respond(status = HttpStatusCode.Created, message = match) }
        }
    }
    // Set the manual calculation order for a group of same-date matches (#331/#332). A collection-level
    // literal path, so it never collides with `/{id}`. HOST/ADMINISTRATOR (enforced in the service).
    put(path = "/calculation-order") {
        respondMappingErrors {
            val ids = call.receive<ReorderMatchesRequest>().matchIds.map { parseUuid(value = it, field = "match id") }
            respondEither(result = service.reorder(token = verifiedToken(), matchIds = ids)) {
                call.respond(status = HttpStatusCode.NoContent, message = "")
            }
        }
    }
}

private fun parseUuid(
    value: String,
    field: String = "user id",
): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid $field '$value'", e)
    }

/**
 * Public match page lookup by code (#136). The literal `code` segment is matched before `/{id}`, so
 * it never collides with the UUID route. Visible to any authenticated user (public-profile semantics).
 */
private fun Route.publicByCode(service: MatchService) {
    get(path = "/code/{code}") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.publicByCode(token = optionalVerifiedToken(), code = code)) { match ->
                call.respond(status = HttpStatusCode.OK, message = match)
            }
        }
    }
}

private fun Route.byId(service: MatchService) {
    get(path = "/{id}") {
        respondMappingErrors {
            respondEither(
                result = service.getById(token = verifiedToken(), matchId = uuidParam(name = "id")),
            ) { match -> call.respond(status = HttpStatusCode.OK, message = match) }
        }
    }
    get(path = "/{id}/calculation") {
        respondMappingErrors {
            respondEither(
                result = service.calculationDetail(token = verifiedToken(), matchId = uuidParam(name = "id")),
            ) { detail -> call.respond(status = HttpStatusCode.OK, message = detail) }
        }
    }
    post(path = "/{id}/result") {
        respondMappingErrors {
            val request = call.receive<MatchResultRequest>()
            respondEither(
                result = service.uploadResult(token = verifiedToken(), matchId = uuidParam(name = "id"), request = request),
            ) { match -> call.respond(status = HttpStatusCode.OK, message = match) }
        }
    }
    put(path = "/{id}/state") {
        respondMappingErrors {
            val request = call.receive<MatchStateRequest>()
            respondEither(
                result = service.setActive(token = verifiedToken(), matchId = uuidParam(name = "id"), active = request.isActive),
            ) { match -> call.respond(status = HttpStatusCode.OK, message = match) }
        }
    }
    fixtureUpdateRoutes(service = service)
}

/**
 * Fixture field-update routes (before the match is rated): per-side rating handicaps (#486). Handicap
 * ranges are validated in the DTO init; the service enforces the unrated guard.
 */
private fun Route.fixtureUpdateRoutes(service: MatchService) {
    put(path = "/{id}/handicaps") {
        respondMappingErrors {
            val request = call.receive<SetHandicapsRequest>()
            respondEither(
                result =
                    service.setHandicaps(
                        token = verifiedToken(),
                        matchId = uuidParam(name = "id"),
                        team1Handicap = request.team1Handicap?.let { BigDecimal(it) },
                        team2Handicap = request.team2Handicap?.let { BigDecimal(it) },
                    ),
            ) { match -> call.respond(status = HttpStatusCode.OK, message = match) }
        }
    }
}
