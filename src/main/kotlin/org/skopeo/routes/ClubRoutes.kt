// SPDX-FileCopyrightText: 2026 Lange Pantoja
// SPDX-License-Identifier: AGPL-3.0-or-later

package org.skopeo.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.skopeo.FIREBASE_AUTH
import org.skopeo.dto.club.AssignOwnerRequest
import org.skopeo.dto.club.CreateClubRequest
import org.skopeo.dto.club.toResponse
import org.skopeo.service.club.ClubService
import java.util.UUID

/**
 * Clubs (#313). ADMINISTRATOR-only (enforced in [ClubService]): create clubs and assign/remove
 * CLUB_OWNER(s). Later work adds an optional club on events and groups the Event Organizer by club.
 */
fun Application.configureClubRoutes(service: ClubService = ClubService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route(path = "/api/v1/clubs") {
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
        }
    }
}
