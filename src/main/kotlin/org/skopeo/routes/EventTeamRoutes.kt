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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.common.dto.event.CreateEventTeamRequest
import org.skopeo.common.dto.event.UpdateEventTeamRequest
import org.skopeo.domain.service.event.EventTeamService
import java.util.UUID

/**
 * Durable, event-scoped teams (#720) under `/api/v1/events/{id}/teams`. Create/list/update/dissolve are
 * staff actions (HOST owns / ADMINISTRATOR or CLUB_OWNER any), enforced in [EventTeamService].
 */
fun Application.configureEventTeamRoutes(service: EventTeamService = EventTeamService()) {
    routing {
        route(path = "/api/v1/events/{id}/teams") {
            authenticate(FIREBASE_AUTH) {
                listAndCreateTeams(service = service)
                updateAndDissolveTeams(service = service)
            }
        }
    }
}

private fun Route.listAndCreateTeams(service: EventTeamService) {
    get {
        respondMappingErrors {
            respondEither(result = service.list(token = verifiedToken(), eventId = uuidParam(name = "id"))) { teams ->
                call.respond(status = HttpStatusCode.OK, message = teams)
            }
        }
    }
    post {
        respondMappingErrors {
            val request = call.receive<CreateEventTeamRequest>()
            respondEither(
                result =
                    service.create(
                        token = verifiedToken(),
                        eventId = uuidParam(name = "id"),
                        memberUserIds = request.memberUserIds.map { parseTeamUuid(value = it) },
                        name = request.name,
                    ),
            ) { team -> call.respond(status = HttpStatusCode.Created, message = team) }
        }
    }
}

private fun Route.updateAndDissolveTeams(service: EventTeamService) {
    patch(path = "/{teamId}") {
        respondMappingErrors {
            val request = call.receive<UpdateEventTeamRequest>()
            respondEither(
                result =
                    service.update(
                        token = verifiedToken(),
                        eventId = uuidParam(name = "id"),
                        teamId = uuidParam(name = "teamId"),
                        memberUserIds = request.memberUserIds?.map { parseTeamUuid(value = it) },
                        name = request.name,
                    ),
            ) { team -> call.respond(status = HttpStatusCode.OK, message = team) }
        }
    }
    delete(path = "/{teamId}") {
        respondMappingErrors {
            respondEither(
                result = service.dissolve(token = verifiedToken(), eventId = uuidParam(name = "id"), teamId = uuidParam(name = "teamId")),
            ) { call.respond(status = HttpStatusCode.NoContent, message = "") }
        }
    }
}

@Suppress("NamedArguments") // java.lang.IllegalArgumentException ctors can't be called with named args.
private fun parseTeamUuid(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid member user id '$value'", e)
    }
