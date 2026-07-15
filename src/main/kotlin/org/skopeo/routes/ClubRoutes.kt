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
import org.skopeo.dto.club.AssignOwnerRequest
import org.skopeo.dto.club.CreateClubRequest
import org.skopeo.dto.club.UpdateClubRequest
import org.skopeo.dto.club.toResponse
import org.skopeo.service.club.ClubService
import java.util.UUID

/**
 * Clubs (#313). Management (create / list / rename / delete / owners) is ADMINISTRATOR/staff-gated
 * (enforced in [ClubService]). The public-by-code page (#327) is viewable anonymously, mirroring the
 * event/match/player public pages.
 */
fun Application.configureClubRoutes(service: ClubService = ClubService()) {
    routing {
        route(path = "/api/v1/clubs") {
            // The public club page is viewable anonymously (#327); the management routes stay required.
            authenticate(FIREBASE_AUTH, optional = true) {
                publicClubByCode(service = service)
            }
            authenticate(FIREBASE_AUTH) {
                listAndCreateClubs(service = service)
                clubMutations(service = service)
                clubOwners(service = service)
            }
        }
    }
}

/**
 * Public club page lookup by code (#327), viewable anonymously (#193). The literal `code` segment
 * matches before `/{id}`, so it never collides with the UUID routes.
 */
private fun Route.publicClubByCode(service: ClubService) {
    get(path = "/code/{code}") {
        respondMappingErrors {
            val code = call.parameters["code"].orEmpty()
            respondEither(result = service.publicByCode(code = code)) { club ->
                call.respond(status = HttpStatusCode.OK, message = club.toResponse())
            }
        }
    }
}

private fun Route.listAndCreateClubs(service: ClubService) {
    post {
        respondMappingErrors {
            val request = call.receive<CreateClubRequest>()
            respondEither(result = service.create(token = verifiedToken(), name = request.name)) { club ->
                call.respond(status = HttpStatusCode.Created, message = club.toResponse())
            }
        }
    }
    get {
        respondMappingErrors {
            respondEither(result = service.list(token = verifiedToken())) { clubs ->
                call.respond(status = HttpStatusCode.OK, message = clubs.map { it.toResponse() })
            }
        }
    }
}

/** Rename (#325) and delete (#325) a club, keyed by id. ADMINISTRATOR-only (enforced in the service). */
private fun Route.clubMutations(service: ClubService) {
    patch(path = "/{id}") {
        respondMappingErrors {
            val name = call.receive<UpdateClubRequest>().name
            respondEither(
                result = service.rename(token = verifiedToken(), clubId = uuidParam(name = "id"), name = name),
            ) { club -> call.respond(status = HttpStatusCode.OK, message = club.toResponse()) }
        }
    }
    delete(path = "/{id}") {
        respondMappingErrors {
            respondEither(result = service.delete(token = verifiedToken(), clubId = uuidParam(name = "id"))) {
                call.respond(status = HttpStatusCode.NoContent, message = "")
            }
        }
    }
}

/** Assign/remove a club's owners. ADMINISTRATOR-only (enforced in the service). */
private fun Route.clubOwners(service: ClubService) {
    post(path = "/{id}/owners") {
        respondMappingErrors {
            val userId = UUID.fromString(call.receive<AssignOwnerRequest>().userId)
            respondEither(
                result = service.assignOwner(token = verifiedToken(), clubId = uuidParam(name = "id"), userId = userId),
            ) { club -> call.respond(status = HttpStatusCode.OK, message = club.toResponse()) }
        }
    }
    delete(path = "/{id}/owners/{userId}") {
        respondMappingErrors {
            respondEither(
                result =
                    service.removeOwner(
                        token = verifiedToken(),
                        clubId = uuidParam(name = "id"),
                        userId = uuidParam(name = "userId"),
                    ),
            ) { club -> call.respond(status = HttpStatusCode.OK, message = club.toResponse()) }
        }
    }
}
