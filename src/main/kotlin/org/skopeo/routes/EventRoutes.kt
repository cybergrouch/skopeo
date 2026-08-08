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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.common.dto.event.AddParticipantRequest
import org.skopeo.common.dto.event.CreateEventRequest
import org.skopeo.common.dto.event.DecideParticipantRequest
import org.skopeo.common.dto.event.SetCalcPriorityRequest
import org.skopeo.common.dto.event.SetEventClubRequest
import org.skopeo.common.dto.event.UpdateEventRequest
import org.skopeo.common.dto.seeding.SaveSeedingOrderRequest
import org.skopeo.domain.service.event.CreateEventInput
import org.skopeo.domain.service.event.EventService
import org.skopeo.domain.service.seeding.SeedingService
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Events/meets (issue #138). Create/list/get and participant management are HOST/ADMINISTRATOR
 * (enforced in [EventService]); an ADMINISTRATOR sees all events while a HOST sees their own.
 */
fun Application.configureEventRoutes(
    service: EventService = EventService(),
    seedingService: SeedingService = SeedingService(),
) {
    routing {
        route(path = "/api/v1/events") {
            // The public event page is viewable anonymously (#193); self-signup + the rest stay required.
            authenticate(FIREBASE_AUTH, optional = true) {
                publicEventByCode(service = service)
            }
            authenticate(FIREBASE_AUTH) {
                listAndCreate(service = service)
                eventSelfSignup(service = service)
                renameEvent(service = service)
                finalizeEvent(service = service)
                byIdAndParticipants(service = service)
                eventSeeding(service = seedingService)
            }
        }
    }
}

/**
 * Event seeding (#714): generate (POST) or read (GET) a deterministic, server-sorted seeding from the
 * event's APPROVED participants — the same seeding + CSV flow as the Seeding tab. Staff-gated
 * (HOST/ADMINISTRATOR/CLUB_OWNER; owner-or-admin), enforced in [SeedingService]/[EventService]; thin.
 */
private fun Route.eventSeeding(service: SeedingService) {
    post(path = "/{id}/seeding") {
        respondMappingErrors {
            respondEither(result = service.generateForEvent(token = verifiedToken(), eventId = uuidParam(name = "id"))) { seeding ->
                call.respond(status = HttpStatusCode.OK, message = seeding)
            }
        }
    }
    put(path = "/{id}/seeding") {
        respondMappingErrors {
            val orderedUserIds = call.receive<SaveSeedingOrderRequest>().userIds.map { UUID.fromString(it) }
            respondEither(
                result =
                    service.saveOrderForEvent(
                        token = verifiedToken(),
                        eventId = uuidParam(name = "id"),
                        orderedUserIds = orderedUserIds,
                    ),
            ) { seeding ->
                call.respond(status = HttpStatusCode.OK, message = seeding)
            }
        }
    }
    get(path = "/{id}/seeding") {
        respondMappingErrors {
            respondEither(result = service.getForEvent(token = verifiedToken(), eventId = uuidParam(name = "id"))) { seeding ->
                call.respond(status = HttpStatusCode.OK, message = seeding)
            }
        }
    }
}

private fun Route.listAndCreate(service: EventService) {
    get {
        respondMappingErrors {
            respondEither(result = service.list(token = verifiedToken())) { events ->
                call.respond(status = HttpStatusCode.OK, message = events)
            }
        }
    }
    post {
        respondMappingErrors {
            val request = call.receive<CreateEventRequest>()
            respondEither(result = service.create(token = verifiedToken(), input = toCreateEventInput(request = request))) { event ->
                call.respond(status = HttpStatusCode.Created, message = event)
            }
        }
    }
    // The caller's own events (#202) for the Profile "Events history". The literal `/mine` segment is
    // matched before `/{id}`, so it never collides with the UUID route. Any authenticated user.
    get(path = "/mine") {
        respondMappingErrors {
            respondEither(result = service.myEvents(token = verifiedToken())) { events ->
                call.respond(status = HttpStatusCode.OK, message = events)
            }
        }
    }
}

/**
 * Public event page lookup by code (#138), viewable anonymously (#193) — a token only personalizes
 * the viewer status. The literal `code` segment matches before `/{id}`, so it never collides with the
 * UUID route.
 */
private fun Route.publicEventByCode(service: EventService) {
    get(path = "/code/{code}") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.publicByCode(token = optionalVerifiedToken(), code = code)) { event ->
                call.respond(status = HttpStatusCode.OK, message = event)
            }
        }
    }
}

/** Self-signup (#201): any authenticated player requests to join the event by its public code. */
private fun Route.eventSelfSignup(service: EventService) {
    post(path = "/code/{code}/signup") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.selfSignup(token = verifiedToken(), code = code)) { event ->
                call.respond(status = HttpStatusCode.OK, message = event)
            }
        }
    }
}

/**
 * Event mutations keyed by id: rename (#269), set club (#319), set calculation priority (#335).
 * Staff-only (HOST owns / ADMINISTRATOR any; calc priority is ADMINISTRATOR-only), enforced in the service.
 */
private fun Route.renameEvent(service: EventService) {
    patch(path = "/{id}") {
        respondMappingErrors {
            val name = requireNotNull(value = call.receive<UpdateEventRequest>().name) { "A name is required to update the event" }
            respondEither(
                result = service.rename(token = verifiedToken(), id = uuidParam(name = "id"), name = name),
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event) }
        }
    }
    put(path = "/{id}/club") {
        respondMappingErrors {
            // A null/absent clubId clears the club (event becomes "Open"); a non-null id must parse + exist.
            val clubId = call.receive<SetEventClubRequest>().clubId?.let { parseEventUuid(value = it, field = "club id") }
            respondEither(
                result = service.setClub(token = verifiedToken(), id = uuidParam(name = "id"), clubId = clubId),
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event) }
        }
    }
    put(path = "/{id}/calculation-priority") {
        respondMappingErrors {
            val priority = call.receive<SetCalcPriorityRequest>().priority
            respondEither(
                result = service.setCalcPriority(token = verifiedToken(), id = uuidParam(name = "id"), priority = priority),
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event) }
        }
    }
}

/**
 * An event's finalize lifecycle: finalize (#403) closes it to changes and queues its matches for
 * rating; un-finalize (#477) reverses that so an erroneous score can be corrected and re-finalized;
 * reverse-ratings (#478) is the rated-path complement that rewinds an already-rated event. Finalize
 * and un-finalize are staff-only (HOST owns / ADMINISTRATOR / CLUB_OWNER); reverse-ratings is
 * ADMINISTRATOR-only. All authz is enforced in the service.
 */
private fun Route.finalizeEvent(service: EventService) {
    post(path = "/{id}/finalize") {
        respondMappingErrors {
            respondEither(
                result = service.finalize(token = verifiedToken(), id = uuidParam(name = "id")),
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event) }
        }
    }
    // Rejected if the event is not finalized, or if any of its matches are already rated.
    post(path = "/{id}/unfinalize") {
        respondMappingErrors {
            respondEither(
                result = service.unfinalize(token = verifiedToken(), id = uuidParam(name = "id")),
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event) }
        }
    }
    // Reverse an already-rated event's ratings (#478), ADMINISTRATOR-only. Rejected if the event is not
    // finalized, has no rated matches, or is not at the rated tip (later matches rated on top).
    post(path = "/{id}/reverse-ratings") {
        respondMappingErrors {
            respondEither(
                result = service.reverseRatings(token = verifiedToken(), id = uuidParam(name = "id")),
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event) }
        }
    }
}

private fun Route.byIdAndParticipants(service: EventService) {
    get(path = "/{id}") {
        respondMappingErrors {
            respondEither(result = service.get(token = verifiedToken(), id = uuidParam(name = "id"))) { event ->
                // Raw participant ratings are ADMINISTRATOR-only (#583); band-only on single-event detail
                // for now (no leak). Admins still see raw on the event list.
                call.respond(status = HttpStatusCode.OK, message = event)
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
                        userId = parseEventUuid(value = request.userId),
                    ),
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event) }
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
                        statusRaw = request.status,
                    ),
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event) }
        }
    }
    // Delete an event (#243). Soft-delete, gated by the event's matches (see EventService.delete).
    delete(path = "/{id}") {
        respondMappingErrors {
            respondEither(result = service.delete(token = verifiedToken(), id = uuidParam(name = "id"))) {
                call.respond(status = HttpStatusCode.NoContent, message = "")
            }
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
            ) { event -> call.respond(status = HttpStatusCode.OK, message = event) }
        }
    }
}

/** Parse + validate the create-event request shape at the boundary (#116): dates and participant ids. */
private fun toCreateEventInput(request: CreateEventRequest): CreateEventInput {
    fun parseDate(
        value: String,
        field: String,
    ): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Invalid $field '$value'; expected ISO-8601 (yyyy-MM-dd)", e)
        }

    return CreateEventInput(
        name = request.name,
        startDate = parseDate(value = request.startDate, field = "startDate"),
        endDate = parseDate(value = request.endDate, field = "endDate"),
        participantIds = request.participantIds.map { parseEventUuid(value = it) },
        clubId = request.clubId?.let { parseEventUuid(value = it, field = "club id") },
        circuitId = request.circuitId?.let { parseEventUuid(value = it, field = "circuit id") },
        format = request.format,
        type = request.type,
        // "Award Ranking Points" (#559) defaults to true when the client omits it.
        awardRankingPoints = request.awardRankingPoints ?: true,
    )
}

private fun parseEventUuid(
    value: String,
    field: String = "user id",
): UUID =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid $field '$value'", e)
    }
