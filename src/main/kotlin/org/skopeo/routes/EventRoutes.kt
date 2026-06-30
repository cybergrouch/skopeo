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
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.event.AddParticipantRequest
import org.skopeo.dto.event.CreateEventRequest
import org.skopeo.dto.event.DecideParticipantRequest
import org.skopeo.dto.event.toResponse
import org.skopeo.model.EventParticipantStatus
import org.skopeo.service.event.CreateEventInput
import org.skopeo.service.event.EventService
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Events/meets (issue #138). Create/list/get and participant management are HOST/ADMINISTRATOR
 * (enforced in [EventService]); an ADMINISTRATOR sees all events while a HOST sees their own.
 */
fun Application.configureEventRoutes(service: EventService = EventService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/events") {
                listAndCreate(service = service)
                myEvents(service = service)
                publicByCode(service = service)
                byIdAndParticipants(service = service)
            }
        }
    }
}

private fun Route.listAndCreate(service: EventService) {
    get {
        respondMappingErrors {
            respondEither(result = service.list(token = verifiedToken())) { events ->
                call.respond(status = HttpStatusCode.OK, message = events.map { it.toResponse() })
            }
        }
    }
    post {
        respondMappingErrors {
            val request = call.receive<CreateEventRequest>()
            respondEither(result = service.create(token = verifiedToken(), input = toCreateEventInput(request = request))) { event ->
                call.respond(status = HttpStatusCode.Created, message = event.toResponse())
            }
        }
    }
}

/**
 * The caller's own events (#202) for the Profile "Events history". The literal `/mine` segment is
 * matched before `/{id}`, so it never collides with the UUID route. Any authenticated user.
 */
private fun Route.myEvents(service: EventService) {
    get(path = "/mine") {
        respondMappingErrors {
            respondEither(result = service.myEvents(token = verifiedToken())) { events ->
                call.respond(status = HttpStatusCode.OK, message = events.map { it.toResponse() })
            }
        }
    }
}

/**
 * Public event page lookup by code (#138). The literal `code` segment matches before `/{id}`, so it
 * never collides with the UUID route. Visible to any authenticated user (public-page semantics).
 */
private fun Route.publicByCode(service: EventService) {
    get(path = "/code/{code}") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.publicByCode(token = verifiedToken(), code = code)) { event ->
                call.respond(status = HttpStatusCode.OK, message = event)
            }
        }
    }
    // Self-signup (#201): any authenticated player requests to join the event by its public code.
    post(path = "/code/{code}/signup") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.selfSignup(token = verifiedToken(), code = code)) { event ->
                call.respond(status = HttpStatusCode.OK, message = event)
            }
        }
    }
}

private fun Route.byIdAndParticipants(service: EventService) {
    get(path = "/{id}") {
        respondMappingErrors {
            respondEither(result = service.get(token = verifiedToken(), id = uuidParam(name = "id"))) { event ->
                call.respond(status = HttpStatusCode.OK, message = event.toResponse())
            }
        }
    }
    post(path = "/{id}/participants") {
        respondMappingErrors {
            val request = call.receive<AddParticipantRequest>()
            respondEither(
                result =
                    service.addParticipant(
                        token = verifiedToken(),
                        eventId = uuidParam(name = "id"),
                        userId = parseEventUserId(value = request.userId),
                    ),
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event.toResponse()) }
        }
    }
    // Approve or hold a participant request (#201). Staff-only (enforced in the service).
    post(path = "/{id}/participants/{userId}/decision") {
        respondMappingErrors {
            val request = call.receive<DecideParticipantRequest>()
            respondEither(
                result =
                    service.decideParticipant(
                        token = verifiedToken(),
                        eventId = uuidParam(name = "id"),
                        userId = uuidParam(name = "userId"),
                        status = parseParticipantStatus(value = request.status),
                    ),
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event.toResponse()) }
        }
    }
    delete(path = "/{id}/participants/{userId}") {
        respondMappingErrors {
            respondEither(
                result =
                    service.removeParticipant(
                        token = verifiedToken(),
                        eventId = uuidParam(name = "id"),
                        userId = uuidParam(name = "userId"),
                    ),
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event.toResponse()) }
        }
    }
}

/** Parse a participant decision (#201) at the boundary: APPROVED or HOLD, else a 400. */
private fun parseParticipantStatus(value: String): EventParticipantStatus =
    requireNotNull(value = EventParticipantStatus.entries.firstOrNull { it.name == value }) {
        "Invalid decision '$value'; expected APPROVED or HOLD"
    }

/** Parse + validate the create-event request shape at the boundary (#116): dates and participant ids. */
private fun toCreateEventInput(request: CreateEventRequest): CreateEventInput =
    CreateEventInput(
        name = request.name,
        startDate = parseEventDate(value = request.startDate, field = "startDate"),
        endDate = parseEventDate(value = request.endDate, field = "endDate"),
        participantIds = request.participantIds.map { parseEventUserId(value = it) },
    )

private fun parseEventDate(
    value: String,
    field: String,
): LocalDate =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Invalid $field '$value'; expected ISO-8601 (yyyy-MM-dd)", e)
    }

private fun parseEventUserId(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid user id '$value'", e)
    }
