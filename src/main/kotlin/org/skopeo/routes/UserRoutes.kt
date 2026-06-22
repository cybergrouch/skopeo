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
import org.skopeo.dto.user.CreateUserRequest
import org.skopeo.dto.user.ProfileRequest
import org.skopeo.dto.user.toResponse
import org.skopeo.service.user.UserService
import org.skopeo.service.user.toProfilePatch

/**
 * User-management API. Identity is taken from the verified Firebase token; access to
 * a specific user is restricted to that user or an ADMINISTRATOR (enforced in the
 * service). Routes stay thin — parse, delegate, map errors to status codes.
 */
fun Application.configureUserRoutes(service: UserService = UserService()) {
    routing {
        authenticate(FIREBASE_AUTH) {
            route("/api/v1/users") {
                createUser(service)
                currentUser(service)
                userById(service)
            }
        }
    }
}

private fun Route.createUser(service: UserService) {
    post {
        respondMappingErrors {
            val request = call.receive<CreateUserRequest>()
            val result = service.provision(token = verifiedToken(), request = request)
            val status = if (result.created) HttpStatusCode.Created else HttpStatusCode.OK
            call.respond(status = status, message = result.user.toResponse())
        }
    }
}

private fun Route.currentUser(service: UserService) {
    get("/me") {
        respondMappingErrors {
            val user = service.currentUser(verifiedToken())
            if (user == null) {
                call.respond(
                    status = HttpStatusCode.NotFound,
                    message = errorBody(error = "Not provisioned", message = "POST /api/v1/users to create your profile"),
                )
            } else {
                call.respond(status = HttpStatusCode.OK, message = user.toResponse())
            }
        }
    }
}

private fun Route.userById(service: UserService) {
    get("/{id}") {
        respondMappingErrors {
            val user = service.getById(token = verifiedToken(), id = uuidParam("id"))
            call.respond(status = HttpStatusCode.OK, message = user.toResponse())
        }
    }
    patch("/{id}") {
        respondMappingErrors {
            val patch = call.receive<ProfileRequest>().toProfilePatch()
            val user = service.patchProfile(token = verifiedToken(), id = uuidParam("id"), patch = patch)
            call.respond(status = HttpStatusCode.OK, message = user.toResponse())
        }
    }
    put("/{id}") {
        respondMappingErrors {
            val patch = call.receive<ProfileRequest>().toProfilePatch()
            val user = service.replaceProfile(token = verifiedToken(), id = uuidParam("id"), patch = patch)
            call.respond(status = HttpStatusCode.OK, message = user.toResponse())
        }
    }
    delete("/{id}") {
        respondMappingErrors {
            service.deactivate(token = verifiedToken(), id = uuidParam("id"))
            call.respond(status = HttpStatusCode.NoContent, message = "")
        }
    }
}
