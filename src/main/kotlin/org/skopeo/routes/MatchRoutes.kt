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
import org.skopeo.dto.match.ReorderMatchesRequest
import org.skopeo.dto.match.SetDesignationRequest
import org.skopeo.dto.match.toResponse
import org.skopeo.dto.rating.toResponse
import org.skopeo.model.MatchQuery
import org.skopeo.model.MatchType
import org.skopeo.model.TeamType
import org.skopeo.service.match.FixtureInput
import org.skopeo.service.match.MatchService
import java.time.LocalDate
import java.time.format.DateTimeParseException
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
            val view = matchQueryOf(value = call.request.queryParameters["filter"])
            val eventId = call.request.queryParameters["eventId"]?.let { parseUuid(value = it) }
            respondEither(result = service.query(token = verifiedToken(), view = view, eventId = eventId)) { list ->
                call.respond(status = HttpStatusCode.OK, message = list.map { it.toResponse() })
            }
        }
    }
    post {
        respondMappingErrors {
            val request = call.receive<CreateFixtureRequest>()
            respondEither(
                result = service.createFixture(token = verifiedToken(), request = toFixtureInput(request = request)),
            ) { match -> call.respond(status = HttpStatusCode.Created, message = match.toResponse()) }
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

/** Parse + validate the fixture request shape at the boundary (#116): enums, date, ids, composition. */
private fun toFixtureInput(request: CreateFixtureRequest): FixtureInput {
    val matchFormat = parseEnumParam<TeamType>(value = request.matchFormat, field = "matchFormat")
    val team1 = request.team1.map { parseUuid(value = it) }
    val team2 = request.team2.map { parseUuid(value = it) }
    validateComposition(type = matchFormat, team1 = team1, team2 = team2)
    // Designated points (#403 Phase C) are whole and non-negative (decision #6) — reject at the boundary.
    request.designatedPoints?.let { require(value = it > 0) { "designatedPoints must be a positive integer" } }
    return FixtureInput(
        matchFormat = matchFormat,
        matchType = parseEnumParam<MatchType>(value = request.matchType, field = "matchType"),
        matchDate = parseMatchDate(value = request.matchDate),
        team1 = team1,
        team2 = team2,
        venue = request.venue,
        tournamentName = request.tournamentName,
        eventId = request.eventId?.let { parseUuid(value = it, field = "event id") },
        designatedPoints = request.designatedPoints,
        awardPoints = request.awardPoints,
    )
}

private fun validateComposition(
    type: TeamType,
    team1: List<UUID>,
    team2: List<UUID>,
) {
    val expected = if (type == TeamType.SINGLES) 1 else 2
    require(value = team1.size == expected && team2.size == expected) { "$type needs $expected player(s) per side" }
    val all = team1 + team2
    require(value = all.toSet().size == all.size) { "a player cannot appear more than once in a match" }
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

private fun parseMatchDate(value: String): LocalDate =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Invalid matchDate '$value'; expected ISO-8601 (yyyy-MM-dd)", e)
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
            ) { match -> call.respond(status = HttpStatusCode.OK, message = match.toResponse()) }
        }
    }
    get(path = "/{id}/calculation") {
        respondMappingErrors {
            respondEither(
                result = service.calculationDetail(token = verifiedToken(), matchId = uuidParam(name = "id")),
            ) { detail -> call.respond(status = HttpStatusCode.OK, message = detail.toResponse()) }
        }
    }
    post(path = "/{id}/result") {
        respondMappingErrors {
            val request = call.receive<MatchResultRequest>()
            respondEither(
                result = service.uploadResult(token = verifiedToken(), matchId = uuidParam(name = "id"), request = request),
            ) { match -> call.respond(status = HttpStatusCode.OK, message = match.toResponse()) }
        }
    }
    put(path = "/{id}/state") {
        respondMappingErrors {
            val request = call.receive<MatchStateRequest>()
            respondEither(
                result = service.setActive(token = verifiedToken(), matchId = uuidParam(name = "id"), active = request.isActive),
            ) { match -> call.respond(status = HttpStatusCode.OK, message = match.toResponse()) }
        }
    }
    // Set (or clear) a fixture's designated points (#466 opt-in "award points for this match" checkbox).
    put(path = "/{id}/designation") {
        respondMappingErrors {
            val request = call.receive<SetDesignationRequest>()
            request.designatedPoints?.let { require(value = it > 0) { "designatedPoints must be a positive integer" } }
            respondEither(
                result =
                    service.setDesignation(
                        token = verifiedToken(),
                        matchId = uuidParam(name = "id"),
                        designatedPoints = request.designatedPoints,
                    ),
            ) { match -> call.respond(status = HttpStatusCode.OK, message = match.toResponse()) }
        }
    }
}

private fun matchQueryOf(value: String?): MatchQuery =
    when (value) {
        "pending-calculation" -> MatchQuery.PENDING_CALCULATION
        "awaiting-results" -> MatchQuery.AWAITING_RESULTS
        "results" -> MatchQuery.RESULTS
        else -> throw IllegalArgumentException("filter must be 'pending-calculation', 'awaiting-results', or 'results'")
    }
