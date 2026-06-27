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
            val match = service.createFixture(token = verifiedToken(), request = toFixtureInput(request = request))
            call.respond(status = HttpStatusCode.Created, message = match.toResponse())
        }
    }
}

/** Parse + validate the fixture request shape at the boundary (#116): enums, date, ids, composition. */
private fun toFixtureInput(request: CreateFixtureRequest): FixtureInput {
    val matchFormat = parseEnumParam<TeamType>(value = request.matchFormat, field = "matchFormat")
    val team1 = request.team1.map { parseUserId(value = it) }
    val team2 = request.team2.map { parseUserId(value = it) }
    validateComposition(type = matchFormat, team1 = team1, team2 = team2)
    return FixtureInput(
        matchFormat = matchFormat,
        matchType = parseEnumParam<MatchType>(value = request.matchType, field = "matchType"),
        matchDate = parseMatchDate(value = request.matchDate),
        team1 = team1,
        team2 = team2,
        venue = request.venue,
        tournamentName = request.tournamentName,
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

private fun parseUserId(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid user id '$value'", e)
    }

private fun parseMatchDate(value: String): LocalDate =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Invalid matchDate '$value'; expected ISO-8601 (yyyy-MM-dd)", e)
    }

private fun Route.byId(service: MatchService) {
    get(path = "/{id}") {
        respondMappingErrors {
            val match = service.getById(token = verifiedToken(), matchId = uuidParam(name = "id"))
            call.respond(status = HttpStatusCode.OK, message = match.toResponse())
        }
    }
    get(path = "/{id}/calculation") {
        respondMappingErrors {
            val detail = service.calculationDetail(token = verifiedToken(), matchId = uuidParam(name = "id"))
            call.respond(status = HttpStatusCode.OK, message = detail.toResponse())
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
