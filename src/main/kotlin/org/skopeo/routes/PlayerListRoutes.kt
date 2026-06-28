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
import org.skopeo.dto.seeding.AddMemberRequest
import org.skopeo.dto.seeding.CreatePlayerListRequest
import org.skopeo.dto.seeding.toResponse
import org.skopeo.dto.seeding.toSummaryResponse
import org.skopeo.service.seeding.PlayerListService
import org.skopeo.service.seeding.SeedingService
import java.util.UUID

/**
 * Host seeding API (issue #111), nested under `/api/v1/player-lists`. HOST/CLUB_OWNER/ADMINISTRATOR
 * only (enforced in [PlayerListService]/[SeedingService]); routes stay thin.
 */
fun Application.configurePlayerListRoutes(
    listService: PlayerListService = PlayerListService(),
    seedingService: SeedingService = SeedingService(),
) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/player-lists") {
                listsCollection(service = listService)
                listById(service = listService)
                members(service = listService)
                seeding(service = seedingService)
            }
        }
    }
}

private fun Route.listsCollection(service: PlayerListService) {
    get {
        respondMappingErrors {
            respondEither(result = service.listMine(token = verifiedToken())) { lists ->
                call.respond(status = HttpStatusCode.OK, message = lists.map { it.toSummaryResponse() })
            }
        }
    }
    post {
        respondMappingErrors {
            val request = call.receive<CreatePlayerListRequest>()
            respondEither(result = service.create(token = verifiedToken(), name = request.name)) { list ->
                call.respond(status = HttpStatusCode.Created, message = list.toSummaryResponse())
            }
        }
    }
}

private fun Route.listById(service: PlayerListService) {
    get(path = "/{id}") {
        respondMappingErrors {
            respondEither(result = service.detail(token = verifiedToken(), listId = uuidParam(name = "id"))) { detail ->
                call.respond(status = HttpStatusCode.OK, message = detail)
            }
        }
    }
    delete(path = "/{id}") {
        respondMappingErrors {
            respondEither(result = service.delete(token = verifiedToken(), listId = uuidParam(name = "id"))) {
                call.respond(status = HttpStatusCode.NoContent, message = "")
            }
        }
    }
}

private fun Route.members(service: PlayerListService) {
    post(path = "/{id}/members") {
        respondMappingErrors {
            val request = call.receive<AddMemberRequest>()
            respondEither(
                result =
                    service.addMember(
                        token = verifiedToken(),
                        listId = uuidParam(name = "id"),
                        userId = UUID.fromString(request.userId),
                    ),
            ) { call.respond(status = HttpStatusCode.NoContent, message = "") }
        }
    }
    delete(path = "/{id}/members/{userId}") {
        respondMappingErrors {
            respondEither(
                result =
                    service.removeMember(
                        token = verifiedToken(),
                        listId = uuidParam(name = "id"),
                        userId = uuidParam(name = "userId"),
                    ),
            ) { call.respond(status = HttpStatusCode.NoContent, message = "") }
        }
    }
}

private fun Route.seeding(service: SeedingService) {
    post(path = "/{id}/seeding") {
        respondMappingErrors {
            respondEither(result = service.generate(token = verifiedToken(), listId = uuidParam(name = "id"))) { seeding ->
                call.respond(status = HttpStatusCode.OK, message = seeding.toResponse())
            }
        }
    }
    get(path = "/{id}/seeding") {
        respondMappingErrors {
            respondEither(result = service.get(token = verifiedToken(), listId = uuidParam(name = "id"))) { seeding ->
                call.respond(status = HttpStatusCode.OK, message = seeding.toResponse())
            }
        }
    }
}
